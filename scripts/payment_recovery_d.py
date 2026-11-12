#!/usr/bin/env python3
# 같은 PostgreSQL을 보는 두 JVM에서 실제 lease 만료와 늦은 결과 거절을 검증한다.

import argparse
from contextlib import contextmanager
import csv
from datetime import datetime
import hashlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import signal
import subprocess
import threading
import time
import uuid

from payment_recovery_a1 import (
    AMOUNT, PAYMENT_KEY, ROOT, Database, Trace, app, approval_request, await_log,
    command, create_order, expect_state, post_response, require, token,
)

SCENARIOS = {
    "late-done": ("recover", "if (!validLease(lockedAttempt, leaseToken)) {", "DONE", "CANCELED"),
    "late-error": ("recordLookupFailure", "if (!validLease(attempt, leaseToken)) {", "503", "DONE"),
}


class Gateway(ThreadingHTTPServer):
    def __init__(self, trace, worker, mode, order):
        super().__init__(("127.0.0.1", 0), Handler)
        self.trace, self.worker, self.mode, self.order = trace, worker, mode, order
        self.lookup = threading.Event()
        self.release = threading.Event()
        self.gets = 0
        self.posts = 0
        self.effect = None


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *_):
        pass

    def do_POST(self):
        pg = self.server
        require(pg.worker == "A" and self.path == "/v1/payments/confirm", "예상 밖 PG 승인 요청입니다.")
        if self.headers.get("Transfer-Encoding") == "chunked":
            chunks = []
            while size := int(self.rfile.readline().split(b";", 1)[0], 16):
                chunks.append(self.rfile.read(size))
                require(self.rfile.read(2) == b"\r\n", "잘못된 HTTP 청크입니다.")
            request = json.loads(b"".join(chunks))
        else:
            request = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
        require(request == dict(paymentKey=PAYMENT_KEY, orderId=pg.order["pgOrderId"], amount=AMOUNT),
                "대상 결제와 PG 승인 요청이 일치하지 않습니다.")
        pg.posts += 1
        pg.effect = dict(paymentKey=PAYMENT_KEY, orderId=request["orderId"],
                         totalAmount=AMOUNT, status="DONE")
        pg.trace.event("approval_effect_committed", worker=pg.worker, **pg.effect)
        # 최초 승인 응답을 보내지 않아 복구 후보를 만든다.

    def do_GET(self):
        pg = self.server
        require(self.path == f"/v1/payments/{PAYMENT_KEY}", "대상 결제 이외의 PG 조회입니다.")
        pg.gets += 1
        pg.trace.event("lookup_received", worker=pg.worker, outcome=pg.mode)
        pg.lookup.set()
        if pg.worker == "B":
            require(pg.release.wait(5), "두 번째 lease를 관측하지 못했습니다.")
        if pg.mode == "503":
            payload = {"code": "UNKNOWN_PAYMENT_ERROR"}
            status = 503
        else:
            require(pg.effect is not None, "최초 승인 효과가 없습니다.")
            payload = dict(pg.effect, status=pg.mode)
            if pg.mode == "CANCELED":
                payload["balanceAmount"] = 0
            status = 200
        body = json.dumps(payload).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)
        pg.trace.event("lookup_response", worker=pg.worker, http_status=status, body=payload)


@contextmanager
def serve(trace, worker, mode, order=None):
    with Gateway(trace, worker, mode, order) as pg:
        server = threading.Thread(target=pg.serve_forever, kwargs={"poll_interval": 0.05})
        server.start()
        try:
            yield pg, f"http://127.0.0.1:{pg.server_port}"
        finally:
            pg.release.set()
            pg.shutdown()
            server.join()


@contextmanager
def attach(args, trace, method, guard_line):
    log_path = trace.directory / "A-debugger.log"
    app_log = (trace.directory / "A.log").read_text()
    debug_port = re.search(r"Listening for transport dt_socket at address: (\d+)", app_log)
    require(debug_port is not None, "A JVM의 루프백 디버거 포트가 없습니다.")
    with log_path.open("w") as log:
        debugger = subprocess.Popen(
            [args.java, "-XX:ActiveProcessorCount=2", "--add-modules", "jdk.jdi",
             "-cp", str(args.debugger_classes), "BeforeRecoverySave", debug_port[1], method,
             str(guard_line)], stdin=subprocess.PIPE, stdout=log, stderr=subprocess.STDOUT,
            text=True, env={},
        )
        try:
            await_log(debugger, log_path, "ARMED")
            yield debugger, log_path
        finally:
            if debugger.poll() is None:
                debugger.terminate()
                debugger.wait(timeout=10)


def lease(db):
    return json.loads(db.query("""
        SELECT json_build_object('token', lease_token, 'until', lease_until, 'db_time', localtimestamp,
                                 'remaining_s', extract(epoch FROM lease_until - localtimestamp))
        FROM payment_attempts
    """))


def stop(trace, process):
    os.kill(process.pid, signal.SIGSTOP)
    deadline = time.monotonic() + 5
    while time.monotonic() < deadline:
        pid, status = os.waitpid(process.pid, os.WUNTRACED | os.WNOHANG)
        if pid:
            require(os.WIFSTOPPED(status) and os.WSTOPSIG(status) == signal.SIGSTOP,
                    "A JVM의 SIGSTOP을 확인하지 못했습니다.")
            trace.event("A_stopped", pid=process.pid)
            return
        time.sleep(0.05)
    raise RuntimeError("A JVM의 중단을 기다리다 시간이 초과됐습니다.")


def final_state(state, order, recipients, mode):
    if mode == "DONE":
        expect_state(state, order, ("SUCCESS", "PAID", "SOLD_OUT", "SUCCEEDED"), recipients)
        require(not state["cancellation_notifications"], "취소 알림이 추가됐습니다.")
    else:
        require((state["payment"], state["order"], state["post"], state["attempt"])
                == ("CANCELED", "CANCELED", "ON_SALE", "SUCCEEDED")
                and state["payment_id"] == order["paymentId"] and state["order_id"] == order["orderId"]
                and state["attempt_id"] == order["attemptId"] and state["attempts"] == 1
                and state["operation"] == "CONFIRM" and state["owner"] is None
                and not state["notifications"] and state["cancellation_notifications"] == recipients,
                f"B의 취소 반영 상태가 일치하지 않습니다. {state}")
    require(state["pg_status"] == mode and state["lease_until"] is None
            and state["recovery_state"] == "NONE", "완료 상태 또는 lease 해제가 일치하지 않습니다.")


def run(args, directory, guard_line):
    method, _, a_mode, b_mode = SCENARIOS[args.profile]
    trace = Trace(directory)
    db = Database(args, "d_" + uuid.uuid4().hex)
    secret = secrets.token_hex(32)
    db.query(f"CREATE SCHEMA {db.schema}")
    try:
        with serve(trace, "A", a_mode) as (pg_a, url_a):
            with app(args, db, url_a, secret, trace, "A", "create", debug=True) as (a, port, _):
                seller, buyer, post_id = db.fixture()
                with attach(args, trace, method, guard_line) as (debugger, debug_log):
                    bearer = token(buyer, secret)
                    order = create_order(port, bearer, post_id)
                    pg_a.order = order
                    route, body = approval_request(order)
                    status, payload = post_response(port, route, body, bearer, 202)
                    require(status == 202 and payload["data"]["status"] == "PROCESSING", "미확정 결제가 아닙니다.")
                    require(pg_a.lookup.wait(75), "A의 첫 PG 조회를 확인하지 못했습니다.")
                    pause = await_log(debugger, debug_log, "PAUSED ").split(maxsplit=3)
                    original = lease(db)
                    require(pause[1] == order["attemptId"] and pause[2] == original["token"]
                            and 0 < original["remaining_s"] <= 45 and pg_a.gets == 1,
                            "A의 현재 lease 또는 보류된 결과가 일치하지 않습니다.")
                    if a_mode == "DONE":
                        require(pause[3] == "DONE", "A에서 DONE 응답을 관측하지 못했습니다.")
                    else:
                        require(pause[3] == "토스페이먼츠 요청에 실패했습니다.", "A에서 조회 오류를 관측하지 못했습니다.")
                    trace.event("A_result_held", pid=a.pid, result=pause[3], **original)
                    stop(trace, a)
                    try:
                        return handoff(args, trace, db, secret, a, debugger, debug_log, pg_a, post_id,
                                       order, sorted([seller, buyer]), original, b_mode)
                    finally:
                        # 실패 경로에서도 실험 JVM을 정지 상태에 두지 않는다.
                        if a.poll() is None:
                            os.kill(a.pid, signal.SIGCONT)
    finally:
        db.query(f"DROP SCHEMA {db.schema} CASCADE")


def handoff(args, trace, db, secret, a, debugger, debug_log, pg_a, post_id, order,
            recipients, original, b_mode):
    with serve(trace, "B", b_mode, order) as (pg_b, url_b):
        pg_b.effect = pg_a.effect
        with app(args, db, url_b, secret, trace, "B", "validate") as (b, _, _):
            require(a.pid != b.pid, "두 워커 PID가 일치합니다.")
            require(pg_b.lookup.wait(85), "lease 만료 후 B의 PG 조회를 확인하지 못했습니다.")
            replacement = lease(db)
            delay = (datetime.fromisoformat(replacement["db_time"])
                     - datetime.fromisoformat(original["until"])).total_seconds()
            require(delay >= 0 and replacement["token"] and replacement["token"] != original["token"]
                    and replacement["remaining_s"] > 0 and pg_b.gets == 1,
                    "B가 만료된 lease 이후 새 token을 얻지 못했습니다.")
            trace.event("B_claimed", pid=b.pid, after_expiry_s=delay, **replacement)
            pg_b.release.set()
            target = "SUCCESS" if b_mode == "DONE" else "CANCELED"
            deadline = time.monotonic() + 15
            while time.monotonic() < deadline:
                state = db.state(post_id)
                if state["payment"] == target:
                    break
                time.sleep(0.1)
            else:
                raise RuntimeError("B가 결과를 커밋하지 못했습니다.")
            final_state(state, order, recipients, b_mode)
            trace.event("B_committed", pid=b.pid, **state)

            os.kill(a.pid, signal.SIGCONT)
            trace.event("A_continued", pid=a.pid)
            debugger.stdin.write("resume\n")
            debugger.stdin.flush()
            guard = await_log(debugger, debug_log, "GUARD ").split(maxsplit=3)
            require(guard[1] == order["attemptId"] and guard[2] == original["token"],
                    "A의 이전 token이 거절 분기에 도달하지 않았습니다.")
            # 거절 분기 직전 잡은 행 잠금이 풀릴 때까지 기다려 트랜잭션 종료 뒤를 관측한다.
            db.query("SELECT id FROM payment_attempts FOR UPDATE")
            after = db.state(post_id)
            trace.event("A_result_rejected", pid=a.pid, guard=guard[3], **after)
            final_state(after, order, recipients, b_mode)
            dynamic = {"reservation_age_s", "next_check_pending"}
            require({k: v for k, v in state.items() if k not in dynamic}
                    == {k: v for k, v in after.items() if k not in dynamic},
                    "A의 늦은 결과가 B의 DB 상태를 변경했습니다.")
            require(pg_a.gets == pg_b.gets == 1 and pg_a.posts == 1 and pg_b.posts == 0
                    and a.poll() is None and b.poll() is None, "추가 PG 호출 또는 JVM 종료가 발생했습니다.")
            return dict(profile=args.profile, A_pid=a.pid, B_pid=b.pid, A_result=pg_a.mode,
                        B_result=pg_b.mode, B_claim_after_expiry_s=round(delay, 3),
                        A_remaining_lease_when_paused_s=round(original["remaining_s"], 3),
                        old_result_rejected=True, pg_approval_posts=pg_a.posts,
                        pg_lookup_gets=pg_a.gets + pg_b.gets, final_payment=after["payment"])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--profile", choices=SCENARIOS, required=True)
    parser.add_argument("--jar", type=Path, required=True)
    parser.add_argument("--java", default="java")
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--db-port", type=int, required=True)
    parser.add_argument("--db-user", required=True)
    parser.add_argument("--db-name", required=True)
    parser.add_argument("--repeat", type=int, default=1)
    args = parser.parse_args()
    require(args.jar.is_file() and args.repeat > 0 and args.db_name.isidentifier(),
            "JAR, 회차 수 또는 DB 이름을 확인하세요.")
    args.scenario, args.scan_seconds, args.a4_short_expiry = "D", 30, False
    args.psql = shutil.which("psql")
    require(args.psql is not None, "psql을 PATH에 추가하세요.")
    args.out.mkdir(parents=True)
    args.debugger_classes = args.out / "debugger"
    args.debugger_classes.mkdir()
    java = Path(shutil.which(args.java)).resolve()
    helper = ROOT / "scripts/BeforeRecoverySave.java"
    command(str(java.with_name("javac")), "--add-modules", "jdk.jdi",
            "-d", str(args.debugger_classes), str(helper))
    service = ROOT / "src/main/java/com/kitschcatch/backend/domain/order/service/PaymentRecoveryService.java"
    guard = SCENARIOS[args.profile][1]
    lines = service.read_text().splitlines()
    guard_lines = [i + 2 for i, line in enumerate(lines) if line.strip() == guard]
    require(len(guard_lines) == 1 and lines[guard_lines[0]-1].strip().startswith("return"),
            "이전 lease를 거절하는 줄을 찾지 못했습니다.")
    with args.jar.open("rb") as jar:
        manifest = dict(profile=args.profile, checkout_commit=command("git", "rev-parse", "HEAD", cwd=ROOT),
                        jar_sha256=hashlib.file_digest(jar, "sha256").hexdigest(),
                        runner_sha256=hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
                        debugger_sha256=hashlib.sha256(helper.read_bytes()).hexdigest(),
                        shared_runner_sha256=hashlib.sha256((ROOT/"scripts/payment_recovery_a1.py").read_bytes()).hexdigest(),
                        postgres=Database(args, "public").query("SHOW server_version"),
                        java=subprocess.run([args.java, "-version"], capture_output=True, check=True,
                                            text=True, timeout=15).stderr,
                        lease_s=45, scan_s=30, read_timeout_s=10, repeat=args.repeat)
    (args.out/"manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
    with (args.out/"scenario-results.csv").open("w", newline="") as output:
        for iteration in range(1, args.repeat + 1):
            directory = args.out/f"run-{iteration:02d}"
            directory.mkdir()
            row = dict(run=iteration, **run(args, directory, guard_lines[0]))
            if iteration == 1:
                writer = csv.DictWriter(output, fieldnames=list(row))
                writer.writeheader()
            writer.writerow(row)
            output.flush()
            print(json.dumps(row), flush=True)


if __name__ == "__main__":
    main()
