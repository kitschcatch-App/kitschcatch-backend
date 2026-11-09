#!/usr/bin/env python3
# 시드니 개발 서버에서 배포 상태를 기록하고 실패하면 이전 릴리스를 복구한다.
import fcntl
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import time
from contextlib import contextmanager
from datetime import datetime, timezone
from pathlib import Path
from urllib.parse import urlsplit

SHA = re.compile(r"[0-9a-f]{40}")
DIGEST = re.compile(r"[0-9a-f]{64}")
RELEASE_ID = re.compile(r"([0-9a-f]{40})-[0-9]{1,20}-[0-9]{1,10}")
READINESS = "/api/health/readiness"
OPENAPI = "/v3/api-docs"
ACTIVE = {"QUEUED", "PREFLIGHT", "SWITCHING", "AWAITING_CONFIRMATION", "ROLLING_BACK"}
SWITCHED = {"SWITCHING", "AWAITING_CONFIRMATION", "ROLLING_BACK"}


class DeploymentError(Exception):
    pass


def require(condition, message):
    if not condition:
        raise DeploymentError(message)


def execute(args, error, *, stdin=None, timeout=30):
    try:
        result = subprocess.run(args, input=stdin, capture_output=True, text=True,
                                timeout=timeout, check=False)
    except (OSError, subprocess.TimeoutExpired):
        raise DeploymentError(error) from None
    require(result.returncode == 0, error)
    return result.stdout.strip()


def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def read_json(path):
    return json.loads(path.read_text())


def write_atomic(path, content, mode=0o600):
    with tempfile.NamedTemporaryFile(dir=path.parent, delete=False) as temp:
        staged = Path(temp.name)
        try:
            os.fchmod(temp.fileno(), mode)
            temp.write(content.encode())
            temp.flush()
            os.fsync(temp.fileno())
            os.replace(staged, path)
            directory = os.open(path.parent, os.O_RDONLY | os.O_DIRECTORY)
            try:
                os.fsync(directory)
            finally:
                os.close(directory)
        finally:
            staged.unlink(missing_ok=True)


def write_json(path, data):
    write_atomic(path, json.dumps(data, ensure_ascii=False, sort_keys=True) + "\n")


def read_env(path):
    return dict(line.split("=", 1) for line in path.read_text().splitlines()
                if "=" in line and not line.startswith("#"))


class Server:
    def __init__(self, root):
        self.root = root
        self.program = root / "deploy"
        self.releases = root / "releases"
        self.jobs = self.program / "jobs"
        self.runtime = root / "runtime.env"
        self.config = read_json(self.program / "config.json")
        self.compose = [
            "docker", "compose", "--env-file", str(self.runtime), "-p", "kitschcatch",
            "-f", str(root / "docker-compose.yml"), "-f", str(root / "compose.backend.yml")
        ]
        self.jobs.mkdir(mode=0o700, exist_ok=True)

    @contextmanager
    def lock(self, *, wait=False):
        with (self.root / ".deploy.lock").open("a") as lock:
            try:
                fcntl.flock(lock, fcntl.LOCK_EX if wait else fcntl.LOCK_EX | fcntl.LOCK_NB)
            except BlockingIOError:
                raise DeploymentError("다른 배포가 진행 중입니다.") from None
            yield

    def state(self, job, **changes):
        state_file = job / "state.json"
        current = read_json(state_file) if state_file.exists() else {}
        if changes:
            current.update(changes, releaseId=job.name,
                           updatedAt=datetime.now(timezone.utc).isoformat())
            write_json(state_file, current)
        return current

    def aws(self, args, error):
        return execute(["aws", "--no-cli-pager", "--region", "ap-southeast-2", *args],
                       error, timeout=45)

    def preflight_target(self):
        account = self.aws(["sts", "get-caller-identity", "--query", "Account", "--output", "text"],
                           "시드니 AWS 계정을 확인할 수 없습니다.")
        require(account == self.config["account"], "AWS 계정이 설정과 다릅니다.")
        token = execute(["curl", "-fsS", "--noproxy", "*", "--max-time", "4", "-X", "PUT",
                         "-H", "X-aws-ec2-metadata-token-ttl-seconds: 60",
                         "http://169.254.169.254/latest/api/token"], "EC2 메타데이터 토큰 발급 실패")
        instance = execute(["curl", "-fsS", "--noproxy", "*", "--max-time", "4",
                            "-H", f"X-aws-ec2-metadata-token: {token}",
                            "http://169.254.169.254/latest/meta-data/instance-id"],
                           "EC2 ID 조회 실패")
        require(instance == self.config["instance"], "개발 EC2 ID가 설정과 다릅니다.")
        env = read_env(self.root / "backend.env")
        require(env.get("SPRING_PROFILES_ACTIVE") == "dev", "dev 프로필을 확인할 수 없습니다.")
        require(read_env(self.runtime).get("BACKEND_ENV_FILE") == str(self.root / "backend.env"),
                "Compose 앱과 DB 검사가 서로 다른 환경 파일을 사용합니다.")
        jdbc = env.get("DEV_DB_URL", "")
        require(jdbc.startswith("jdbc:postgresql://"), "개발 PostgreSQL URL 형식 오류")
        target = urlsplit(jdbc.removeprefix("jdbc:"))
        require([target.hostname, target.port or 5432, target.path] == self.config["dbTarget"],
                "DB 접속 대상이 등록된 개발 DB와 다릅니다.")
        require(shutil.disk_usage(self.root).free >= 512 * 1024 * 1024, "배포 디스크 공간 부족")
        self.compose_run(["config", "--quiet"], "Compose 구성을 확인할 수 없습니다.")

    def compose_run(self, args, error, timeout=30):
        return execute([*self.compose, *args], error, timeout=timeout)

    def download(self, job, request):
        stage = job / "artifact"
        stage.mkdir(mode=0o700, exist_ok=True)
        args = ["s3api", "get-object", "--bucket", self.config["bucket"],
                "--expected-bucket-owner", self.config["account"]]
        prefix = f"backend/{job.name}/"
        manifest_path = stage / "release.json"
        self.aws([*args, "--key", prefix + "release.json", str(manifest_path)], "배포 명세 다운로드 실패")
        require(sha256(manifest_path) == request["manifestSha"], "배포 명세 해시 불일치")
        versions = read_json(manifest_path)["artifactVersions"]
        for name in ("app.jar", "schema-contract.jsonl"):
            version = versions[name]
            require(isinstance(version, str) and version not in {"", "null"}, "S3 파일 버전 누락")
            self.aws([*args, "--key", prefix + name, f"--version-id={version}", str(stage / name)],
                     f"배포 파일 다운로드 실패: {name}")
        return stage

    def artifact(self, job, request):
        stage = self.download(job, request) if request["source"] == "s3" else job / "artifact"
        require(sha256(stage / "release.json") == request["manifestSha"], "배포 명세 해시 불일치")
        manifest = read_json(stage / "release.json")
        require(manifest["releaseId"] == job.name and manifest["commit"] == job.name.split("-")[0],
                "배포 명세의 ID 또는 커밋 불일치")
        for name, field in (("app.jar", "jarSha256"), ("schema-contract.jsonl", "schemaSha256")):
            require(sha256(stage / name) == manifest[field], f"파일 해시 불일치: {name}")
        require(manifest.get("health") == "readiness", "지원하지 않는 준비 확인 방식")
        return stage, manifest

    def schema_check(self, contract):
        lines = [json.loads(line) for line in contract.read_text().splitlines() if line]
        tables = sorted(row[1] for row in lines if row[0] == "table")
        require(tables and all(re.fullmatch(r"[a-z][a-z0-9_]*", name) for name in tables),
                "DB 검사 명세의 테이블 형식 오류")
        shell = """
            export PGUSER="$DEV_DB_USERNAME" PGPASSWORD="$DEV_DB_PASSWORD" PGCONNECT_TIMEOUT=8
            export PGOPTIONS="-c default_transaction_read_only=on -c statement_timeout=10000 -c lock_timeout=3000"
            exec psql -X -qAt -v ON_ERROR_STOP=1 -d "${DEV_DB_URL#jdbc:}" -v tables="$1"
        """
        raw = execute(["docker", "run", "--pull", "never", "--rm", "-i", "--network", "host",
                       "--env-file", str(self.root / "backend.env"), "--entrypoint", "/bin/sh",
                       self.config["postgresImage"], "-ec", shell, "sh", ",".join(tables)],
                      "개발 DB 스키마를 조회할 수 없습니다.", stdin=(self.program / "catalog.sql").read_text())
        actual = {tuple(json.loads(line)) for line in raw.splitlines()}
        missing = ["/".join(str(value) for value in row[:3] if value is not None)
                   for row in lines if tuple(row) not in actual]
        require(not missing, "스키마 또는 권한 불일치: " + ", ".join(missing[:15]))

    def running_app(self):
        app = self.compose_run(["ps", "-q", "app"], "앱 컨테이너 조회 실패")
        require(bool(re.fullmatch(r"[0-9a-f]{12,64}", app)), "앱 컨테이너가 하나여야 합니다.")
        return app

    def running_digest(self, app):
        return execute(["docker", "exec", app, "sha256sum", "/app/app.jar"],
                       "실행 JAR 해시 조회 실패").split()[0]

    def current_release(self):
        path = Path(read_env(self.runtime)["BACKEND_RELEASE_DIR"]).resolve()
        require(path.parent == self.releases.resolve(), "이전 릴리스 경로가 올바르지 않습니다.")
        manifest_path = path / "release.json"
        manifest = read_json(manifest_path) if manifest_path.exists() else self.config["baseline"]
        require(SHA.fullmatch(manifest["commit"]) and path.name.startswith(manifest["commit"]),
                "이전 릴리스의 커밋을 확인할 수 없습니다.")
        expected = manifest["jarSha256"]
        require(sha256(path / "app.jar") == expected == self.running_digest(self.running_app()),
                "이전 릴리스의 파일과 실행 JAR가 일치하지 않습니다.")
        contract = path / "schema-contract.jsonl" if manifest_path.exists() else self.program / "baseline-schema.jsonl"
        require(sha256(contract) == manifest["schemaSha256"], "이전 릴리스의 DB 명세 해시 불일치")
        self.schema_check(contract)
        return manifest

    def external(self, path):
        return execute(["curl", "-fsS", "--noproxy", "*", "--max-time", "5",
                        "--proto", "=https", "-H", "Cache-Control: no-cache",
                        self.config["httpsOrigin"] + path], f"외부 HTTPS 검증 실패: {path}", timeout=7)

    def health(self, app, manifest, *, external):
        if manifest["health"] == "readiness":
            raw = (self.external(READINESS) if external else
                   execute(["docker", "exec", app, "wget", "-qO-", "http://127.0.0.1:8080" + READINESS],
                           "앱 내부 준비 확인 실패", timeout=7))
            require(json.loads(raw) == {"status": "UP", "commit": manifest["commit"]},
                    "준비 상태 또는 실행 커밋 불일치")
        if external or manifest["health"] == "legacy":
            raw = (self.external(OPENAPI) if external else
                   execute(["docker", "exec", app, "wget", "-qO-", "http://127.0.0.1:8080" + OPENAPI],
                           "앱 내부 OpenAPI 확인 실패", timeout=7))
            required = {"/api/reports", "/api/admin/reports"}
            require(required <= json.loads(raw)["paths"].keys(), "필수 OpenAPI 경로 누락")
        if external:
            status = execute(["curl", "-sS", "--noproxy", "*", "--max-time", "5", "--proto", "=https",
                              "-o", "/dev/null", "-w", "%{http_code}",
                              self.config["httpsOrigin"] + "/api/users/me"],
                             "인증 경로 HTTPS 검증 실패", timeout=7)
            require(status == "401", "미인증 접근 응답이 401이 아닙니다.")

    def verify(self, app, manifest, *, external):
        deadline = time.monotonic() + 75
        while True:
            try:
                self.health(app, manifest, external=external)
                state = json.loads(execute(["docker", "inspect", app], "컨테이너 상태 조회 실패"))[0]
                require(state["State"]["Running"] and not state["State"]["OOMKilled"]
                        and state["RestartCount"] == 0, "컨테이너 종료 또는 재시작이 발생했습니다.")
                require(self.running_digest(app) == manifest["jarSha256"], "컨테이너 실행 JAR 해시 불일치")
                return
            except (DeploymentError, ValueError, KeyError) as error:
                if time.monotonic() >= deadline:
                    raise DeploymentError(str(error) if isinstance(error, DeploymentError)
                                          else "검증 응답의 JSON 형식이 올바르지 않습니다.") from None
                time.sleep(2)

    def recreate(self, manifest):
        self.compose_run(["config", "--quiet"], "Compose 구성 검사 실패")
        self.compose_run(["up", "-d", "--no-deps", "--force-recreate", "app"],
                         "앱 컨테이너 재생성 실패", timeout=60)
        app = self.running_app()
        self.verify(app, manifest, external=False)
        self.compose_run(["exec", "-T", "nginx", "nginx", "-t"], "Nginx 설정 검사 실패")
        self.compose_run(["exec", "-T", "nginx", "nginx", "-s", "reload"], "Nginx 재로드 실패")
        self.verify(app, manifest, external=True)

    def rollback(self, job, reason):
        self.state(job, status="ROLLING_BACK", error=reason)
        try:
            backup = job / "runtime.env.previous"
            previous = read_json(job / "previous.json")
            path = Path(read_env(backup)["BACKEND_RELEASE_DIR"]).resolve()
            require(path.parent == self.releases.resolve()
                    and sha256(path / "app.jar") == previous["jarSha256"], "이전 JAR를 검증할 수 없습니다.")
            write_atomic(self.runtime, backup.read_text())
            self.recreate(previous)
            self.state(job, status="ROLLED_BACK", restoredCommit=previous["commit"])
        except Exception as error:
            self.state(job, status="ROLLBACK_FAILED", rollbackError=self.safe_error(error))

    @staticmethod
    def safe_error(error):
        return str(error) if isinstance(error, DeploymentError) else type(error).__name__

    def reject_unfinished(self, job):
        status = self.state(job).get("status")
        if status in SWITCHED:
            self.rollback(job, "배포 작업이 완료되기 전에 중단되었습니다.")
        elif status in ACTIVE:
            self.state(job, status="REJECTED", error="전환 전에 배포 작업이 중단되었습니다.")

    def start(self, job, mode, manifest_sha, source):
        require(DIGEST.fullmatch(manifest_sha), "배포 명세 SHA-256 형식 오류")
        request = {"mode": mode, "manifestSha": manifest_sha, "source": source}
        with self.lock():
            job.mkdir(mode=0o700, exist_ok=True)
            if (job / "request.json").exists():
                require(read_json(job / "request.json") == request, "같은 배포 ID의 요청 내용이 다릅니다.")
                return self.state(job)
            require(not any(self.state(other).get("status") in ACTIVE for other in self.jobs.iterdir()
                            if other.is_dir()), "미완료 배포가 있습니다.")
            write_json(job / "request.json", request)
            self.state(job, status="QUEUED")
            try:
                execute(["systemctl", "start", "--no-block", f"kitschcatch-deploy@{job.name}.service"],
                        "배포 서비스 시작 요청 실패")
            except DeploymentError as error:
                self.state(job, status="REJECTED", error=str(error))
                raise
            return self.state(job)

    def run(self, job):
        with self.lock(wait=True):
            if self.state(job).get("status") != "QUEUED":
                self.reject_unfinished(job)
                return
            try:
                request = read_json(job / "request.json")
                self.state(job, status="PREFLIGHT")
                self.preflight_target()
                stage, manifest = self.artifact(job, request)
                self.schema_check(stage / "schema-contract.jsonl")
                previous = self.current_release()
                self.state(job, commit=manifest["commit"], jarSha256=manifest["jarSha256"],
                           previousCommit=previous["commit"])
                if request["mode"] == "preflight":
                    self.state(job, status="PREFLIGHT_OK")
                    return
                release = self.releases / job.name
                release.mkdir(mode=0o755, exist_ok=False)
                for name in ("app.jar", "release.json", "schema-contract.jsonl"):
                    execute(["install", "-m", "644", str(stage / name), str(release / name)],
                            f"릴리스 파일 설치 실패: {name}")
                write_atomic(job / "runtime.env.previous", self.runtime.read_text())
                write_json(job / "previous.json", previous)
                self.state(job, status="SWITCHING")
                original = self.runtime.read_text()
                updated, count = re.subn(r"(?m)^BACKEND_RELEASE_DIR=.*$", f"BACKEND_RELEASE_DIR={release}", original)
                require(count == 1, "Compose 릴리스 경로를 하나만 설정해야 합니다.")
                write_atomic(self.runtime, updated)
                self.recreate(manifest)
                self.state(job, status="AWAITING_CONFIRMATION")
                deadline = time.monotonic() + 150
                while not (job / "confirmed").exists():
                    require(time.monotonic() < deadline, "외부 검증 확인 시간이 초과되었습니다.")
                    time.sleep(2)
                self.state(job, status="DEPLOYED")
            except Exception as error:
                if self.state(job).get("status") in SWITCHED:
                    self.rollback(job, self.safe_error(error))
                else:
                    self.state(job, status="REJECTED", error=self.safe_error(error))
            finally:
                # 배포 결과와 복구 설정은 보존하고 같은 JAR의 작업 사본은 정리한다.
                for name in ("app.jar", "release.json", "schema-contract.jsonl"):
                    (job / "artifact" / name).unlink(missing_ok=True)
                if (job / "artifact").is_dir():
                    (job / "artifact").rmdir()

    def confirm(self, job):
        status = self.state(job).get("status")
        require(status in {"AWAITING_CONFIRMATION", "DEPLOYED"}, "확인할 수 있는 배포 상태가 아닙니다.")
        if status == "AWAITING_CONFIRMATION":
            write_atomic(job / "confirmed", "confirmed\n")
        return self.state(job)

    def recover(self, job=None):
        with self.lock(wait=True):
            for candidate in [job] if job is not None else self.jobs.iterdir():
                if candidate.is_dir() and RELEASE_ID.fullmatch(candidate.name):
                    self.reject_unfinished(candidate)


def main():
    command, *args = sys.argv[1:]
    server = Server(Path("/opt/kitschcatch"))
    if command == "recover" and not args:
        server.recover()
        return
    release_id, *options = args
    require(RELEASE_ID.fullmatch(release_id), "배포 ID 형식 오류")
    job = server.jobs / release_id
    if command in {"start", "start-local"}:
        digest, mode = options
        require(mode in {"preflight", "deploy"}, "지원하지 않는 배포 모드")
        result = server.start(job, mode, digest, "local" if command == "start-local" else "s3")
    elif command == "status":
        result = server.state(job)
    elif command == "confirm":
        result = server.confirm(job)
    elif command == "run":
        server.run(job)
        result = server.state(job)
    elif command == "recover":
        server.recover(job)
        result = server.state(job)
    else:
        raise DeploymentError("지원하지 않는 배포 명령입니다.")
    print(json.dumps(result, ensure_ascii=False))


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        print(Server.safe_error(error), file=sys.stderr)
        sys.exit(1)
