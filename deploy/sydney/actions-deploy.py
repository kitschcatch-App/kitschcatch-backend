#!/usr/bin/env python3
# Actions의 배포 파일을 전송하고 서버 결과와 외부 HTTPS를 확인한다.
import json
import os
import re
import subprocess
import sys
import time
from pathlib import Path
from urllib.parse import urlsplit

from deploy import DeploymentError, OPENAPI, READINESS, RELEASE_ID, execute, read_json, require, sha256, write_json


class ActionsDeployment:
    def __init__(self, stage):
        self.stage = stage
        self.release = os.environ["RELEASE_ID"]
        self.mode = os.environ["MODE"]
        self.account = os.environ["AWS_ACCOUNT"]
        self.bucket = os.environ["ARTIFACT_BUCKET"]
        self.instance = os.environ["INSTANCE_ID"]
        self.document = os.environ["SSM_DOCUMENT"]
        self.target_group = os.environ["TARGET_GROUP_ARN"]
        self.origin = os.environ["HTTPS_ORIGIN"]
        origin = urlsplit(self.origin)
        require(origin.scheme == "https" and origin.hostname and
                self.origin == f"https://{origin.hostname}", "HTTPS 원점 형식 오류")
        require(RELEASE_ID.fullmatch(self.release) and self.mode in {"preflight", "deploy"}, "배포 입력 오류")
        require(re.fullmatch(r"[0-9]{12}", self.account) and self.bucket and self.instance
                and self.document and self.target_group, "시드니 배포 설정 누락")
        self.manifest = read_json(stage / "release.json")
        require(self.manifest["releaseId"] == self.release
                and self.manifest["commit"] == self.release.split("-")[0], "업로드할 배포 ID 또는 SHA 불일치")

    def aws(self, args, error, timeout=45):
        return execute(["aws", "--no-cli-pager", "--region", "ap-southeast-2", *args], error, timeout=timeout)

    def put(self, name):
        result = self.aws(["s3api", "put-object", "--bucket", self.bucket, "--expected-bucket-owner", self.account,
                           "--key", f"backend/{self.release}/{name}", "--body", str(self.stage / name),
                           "--if-none-match", "*", "--output", "json"], f"S3 업로드 실패: {name}")
        version = json.loads(result).get("VersionId")
        require(version and version != "null", "배포 버킷에 객체 버전이 없습니다.")
        return version

    def upload(self):
        caller = self.aws(["sts", "get-caller-identity", "--query", "Account", "--output", "text"],
                          "AWS 배포 계정 조회 실패")
        require(caller == self.account, "AWS 배포 계정 불일치")
        versions = {}
        for name, field in (("app.jar", "jarSha256"), ("schema-contract.jsonl", "schemaSha256")):
            require(sha256(self.stage / name) == self.manifest[field], f"업로드 파일 해시 불일치: {name}")
            versions[name] = self.put(name)
        self.manifest["artifactVersions"] = versions
        write_json(self.stage / "release.json", self.manifest)
        self.put("release.json")

    def command(self, operation, digest="none"):
        parameters = json.dumps({"operation": [operation], "releaseId": [self.release],
                                 "manifestSha": [digest], "mode": [self.mode]})
        command = self.aws(["ssm", "send-command", "--document-name", self.document,
                            "--instance-ids", self.instance, "--parameters", parameters,
                            "--timeout-seconds", "30", "--query", "Command.CommandId", "--output", "text"],
                           f"SSM {operation} 요청 실패")
        deadline = time.monotonic() + 45
        while time.monotonic() < deadline:
            invoke = subprocess.run(
                ["aws", "--no-cli-pager", "--region", "ap-southeast-2", "ssm", "get-command-invocation",
                 "--command-id", command, "--instance-id", self.instance, "--output", "json"],
                capture_output=True, text=True, timeout=10, check=False
            )
            if invoke.returncode:
                require("InvocationDoesNotExist" in invoke.stderr, f"SSM 결과 조회 실패: {command}")
            else:
                result = json.loads(invoke.stdout)
                if result["Status"] == "Success":
                    require(result["ResponseCode"] == 0, f"SSM 프로그램 실패: {command}")
                    state = json.loads(result["StandardOutputContent"])
                    require(state.get("releaseId") == self.release, "SSM 배포 ID 불일치")
                    return state
                require(result["Status"] in {"Pending", "InProgress", "Delayed"},
                        f"SSM {operation} 실패: {result['Status']} ({command})")
            time.sleep(2)
        raise DeploymentError(f"SSM {operation} 결과 대기 시간 초과: {command}")

    def verify_public(self, commit):
        def get(path):
            return json.loads(execute(["curl", "-fsS", "--max-time", "6", "--proto", "=https",
                                       "-H", "Cache-Control: no-cache", self.origin + path],
                                      f"외부 HTTPS 검증 실패: {path}", timeout=8))

        require(get(READINESS) == {"status": "UP", "commit": commit}, "외부 HTTPS 실행 커밋 불일치")
        require({"/api/reports", "/api/admin/reports"} <= get(OPENAPI)["paths"].keys(),
                "외부 HTTPS의 필수 경로 누락")
        health = self.aws(["elbv2", "describe-target-health", "--target-group-arn", self.target_group,
                           "--targets", f"Id={self.instance}", "--query",
                           "TargetHealthDescriptions[0].TargetHealth.State", "--output", "text"],
                          "ALB 대상 상태 조회 실패", timeout=8)
        require(health == "healthy", f"ALB 대상이 healthy가 아닙니다: {health}")

    def monitor(self):
        deadline = time.monotonic() + 12 * 60
        verification_deadline = None
        confirmed = False
        while time.monotonic() < deadline:
            state = self.command("status")
            status = state["status"]
            if status in {"PREFLIGHT_OK", "DEPLOYED", "REJECTED", "ROLLED_BACK", "ROLLBACK_FAILED"}:
                return state
            if status == "AWAITING_CONFIRMATION" and not confirmed:
                if verification_deadline is None:
                    verification_deadline = time.monotonic() + 70
                if time.monotonic() < verification_deadline:
                    try:
                        self.verify_public(self.manifest["commit"])
                    except DeploymentError as error:
                        print(str(error), flush=True)
                    else:
                        self.command("confirm")
                        confirmed = True
            time.sleep(6)
        raise DeploymentError("배포 완료 대기 시간 초과. 서버에서 해당 배포 ID의 결과를 조회하세요.")

    def summarize(self, state):
        lines = [
            "## 시드니 개발 EC2",
            f"- 배포 ID: `{self.release}`",
            f"- 대상 커밋: `{self.manifest['commit']}`",
            f"- JAR SHA-256: `{self.manifest['jarSha256']}`",
            f"- 이전 커밋: `{state.get('previousCommit', '미확인')}`",
            f"- 결과: `{state['status']}`",
        ]
        for key, label in (("error", "실패 지점"), ("restoredCommit", "복구 커밋"), ("rollbackError", "복구 실패")):
            if key in state:
                lines.append(f"- {label}: {state[key]}")
        with open(os.environ["GITHUB_STEP_SUMMARY"], "a") as summary:
            summary.write("\n".join(lines) + "\n")
        print(json.dumps(state, ensure_ascii=False))


if __name__ == "__main__":
    try:
        deployment = ActionsDeployment(Path(sys.argv[1]))
        deployment.upload()
        deployment.command("start", sha256(deployment.stage / "release.json"))
        result = deployment.monitor()
        deployment.summarize(result)
        expected = "PREFLIGHT_OK" if deployment.mode == "preflight" else "DEPLOYED"
        require(result["status"] == expected, "배포가 성공하지 않았습니다.")
    except DeploymentError as error:
        print(str(error), file=sys.stderr)
        sys.exit(1)
