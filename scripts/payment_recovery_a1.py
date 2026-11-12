#!/usr/bin/env python3
# PG 승인·취소 중 장애와 복구 커밋 실패 뒤 결제 상태를 검증한다.

import argparse
import base64
from contextlib import contextmanager
from concurrent.futures import ThreadPoolExecutor
import csv
import hashlib
import hmac
from http.client import RemoteDisconnected
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
from pathlib import Path
import re
import secrets
import shutil
import signal
import socket
import subprocess
import threading
import time
from urllib.error import HTTPError
import urllib.request
import uuid

AMOUNT = 12000
PAYMENT_KEY = "a1-test-payment"
READ_TIMEOUT = 10
SHORT_RESERVATION_TTL = 15
SHORT_EXPIRATION_SCAN = 2
ROOT = Path(__file__).resolve().parents[1]
HTTP = urllib.request.build_opener(urllib.request.ProxyHandler({}))


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def command(*args, **kwargs):
    return subprocess.check_output(args, text=True, timeout=15, **kwargs).strip()


def token(user_id, secret):
    def encode(value):
        return base64.urlsafe_b64encode(value).rstrip(b"=").decode()

    now = int(time.time())
    claims = dict(sub=str(user_id), iss="kitschcatch", type="access", iat=now, exp=now + 600)
    payload = encode(b'{"alg":"HS256"}') + "." + encode(json.dumps(claims).encode())
    return payload + "." + encode(hmac.digest(secret.encode(), payload.encode(), "sha256"))


def post_response(port, path, body, bearer, expected_status):
    request = urllib.request.Request(
        f"http://127.0.0.1:{port}{path}", json.dumps(body).encode(),
        {"Content-Type": "application/json", "Authorization": "Bearer " + bearer},
    )
    try:
        response = HTTP.open(request, timeout=20)
    except HTTPError as error:
        if error.code != expected_status:
            raise
        response = error
    with response:
        payload = json.load(response)
        require(response.status == expected_status and payload["success"] == (expected_status < 400),
                str(payload))
        return response.status, payload


def post(port, path, body, bearer, expected_status):
    return post_response(port, path, body, bearer, expected_status)[1]["data"]


class Trace:
    def __init__(self, directory):
        self.directory = directory
        self.start = time.monotonic()
        self.lock = threading.Lock()

    def event(self, name, **fields):
        with self.lock, (self.directory / "events.jsonl").open("a") as output:
            elapsed = round(time.monotonic() - self.start, 6)
            output.write(json.dumps(dict(seconds=elapsed, event=name, **fields)) + "\n")
        return elapsed


class Database:
    def __init__(self, args, schema):
        self.args = args
        self.schema = schema

    def psql(self, *args, **kwargs):
        return command(
            self.args.psql, "-XAtqw", "-v", "ON_ERROR_STOP=1", "-h", "127.0.0.1",
            "-p", str(self.args.db_port), "-U", self.args.db_user,
            "-d", self.args.db_name, *args,
            env={"PGOPTIONS": f"-c search_path={self.schema} -c client_min_messages=warning",
                 "PGCLIENTENCODING": "UTF8"}, **kwargs,
        )

    def query(self, sql):
        return self.psql("-c", sql)

    def install_attempt_indexes(self):
        migration = (ROOT / "src/main/resources/db/manual/029_payment_recovery.sql").read_text()
        indexes = re.findall(r"CREATE UNIQUE INDEX uk_payment_attempts_[^;]+;", migration)
        require(len(indexes) == 3, "029의 결제 시도 부분 유일 인덱스를 찾지 못했습니다.")
        self.query("\n".join(indexes))

    def fixture(self):
        self.install_attempt_indexes()
        ids = self.query("""
            INSERT INTO users (nickname, auth_provider, provider_user_id, created_at)
            VALUES ('seller', 'KAKAO', 'seller', now()), ('buyer', 'KAKAO', 'buyer', now())
            RETURNING id
        """).splitlines()
        seller, buyer = map(int, ids)
        return seller, buyer, self.new_post(seller)

    def new_post(self, seller):
        return int(self.query(f"""
            INSERT INTO posts (user_id, title, description, price, product_category,
                               product_condition, product_status, created_at, updated_at)
            VALUES ({seller}, 'A1', 'A1 fixture', {AMOUNT}, 'GOODS', 'NEW', 'ON_SALE', now(), now())
            RETURNING id
        """))

    def state(self, post_id):
        return json.loads(self.query(f"""
            SELECT json_build_object(
                'payment', p.payment_status, 'order', o.order_status, 'post', s.product_status,
                'owner', s.active_order_number, 'order_id', o.order_number,
                'payment_id', p.payment_id, 'attempt_id', a.attempt_id,
                'attempt', a.attempt_status, 'operation', a.operation,
                'check_count', a.check_count, 'next_check_at', a.next_check_at,
                'lease_until', a.lease_until, 'recovery_state', p.recovery_state,
                'failure_reason', a.failure_reason, 'pg_status', a.pg_status,
                'approved_at', p.approved_at, 'last_verified_at', p.last_verified_at,
                'payment_version', p.state_version, 'attempt_version', a.state_version,
                'completed_at', a.completed_at,
                'shipment_registered_at', o.shipment_registered_at,
                'reservation_expires_at', o.reservation_expires_at,
                'reservation_expired', o.reservation_expires_at <= localtimestamp,
                'next_check_pending', a.next_check_at > localtimestamp,
                'approved_confirm_attempt_id', (SELECT attempt_id FROM payment_attempts
                    WHERE payment_id = p.id AND operation = 'CONFIRM' AND attempt_status = 'SUCCEEDED'),
                'cancel_attempts', (SELECT count(*) FROM payment_attempts
                    WHERE payment_id = p.id AND operation = 'CANCEL'),
                'cancellation_notifications', coalesce((SELECT json_agg(user_id ORDER BY user_id)
                    FROM notifications WHERE type = 'PAYMENT_CANCELED' AND target_id = o.order_number),
                    '[]'::json),
                'attempts', (SELECT count(*) FROM payment_attempts WHERE payment_id = p.id),
                'notifications', coalesce((SELECT json_agg(user_id ORDER BY user_id)
                    FROM notifications WHERE type = 'PAYMENT_SUCCESS'), '[]'::json))
            FROM posts s JOIN orders o ON o.post_id = s.id
            JOIN payments p ON p.order_id = o.id
            JOIN payment_attempts a ON a.payment_id = p.id AND a.attempt_id = p.current_attempt_id
            WHERE s.id = {post_id}
            ORDER BY o.id DESC LIMIT 1
        """))


class PaymentGateway:
    def __init__(self, trace, scenario):
        self.trace = trace
        self.scenario = scenario
        self.drop_requested = threading.Event()
        self.response_dropped_at = None
        self.approved = threading.Event()
        self.allow_lookup = threading.Event()
        self.finished = threading.Event()
        self.lock = threading.Lock()
        self.approvals = 0
        self.lookups = 0
        self.effects = {}
        self.effect_time = None
        self.canceled = threading.Event()
        self.cancels = 0
        self.cancel_effects = {}
        self.cancel_effect_time = None

    @contextmanager
    def serve(self):
        gateway = self

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *_):
                pass

            def send_json(self, status, data):
                body = json.dumps(data).encode()
                self.send_response(status)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)

            def request_body(self):
                if self.headers.get("Transfer-Encoding") != "chunked":
                    return json.loads(self.rfile.read(int(self.headers["Content-Length"])))
                chunks = []
                while length := int(self.rfile.readline().split(b";", 1)[0], 16):
                    chunks.append(self.rfile.read(length))
                    require(self.rfile.read(2) == b"\r\n", "잘못된 HTTP 청크입니다.")
                return json.loads(b"".join(chunks))

            def do_POST(self):
                if gateway.scenario == "A3" and self.path == f"/v1/payments/{PAYMENT_KEY}/cancel":
                    self.cancel()
                    return
                if self.path != "/v1/payments/confirm":
                    self.send_error(404)
                    return
                request = self.request_body()
                key = self.headers.get("Idempotency-Key")
                with gateway.lock:
                    gateway.approvals += 1
                    gateway.trace.event("approval_received", request=request, idempotency_key=key)
                    if key and key not in gateway.effects:
                        gateway.effects[key] = dict(paymentKey=request["paymentKey"],
                                                    orderId=request["orderId"],
                                                    totalAmount=request["amount"], status="DONE")
                        gateway.effect_time = gateway.trace.event(
                            "approval_effect_committed", **gateway.effects[key]
                        )
                    gateway.approved.set()
                    effect = gateway.effects.get(key)
                if gateway.scenario == "A3":
                    self.send_json(200, effect)
                    return
                # 실험기가 승인 효과와 커밋 상태를 확인할 때까지 응답을 보류한다.
                if not gateway.finished.wait(120):
                    return
                if gateway.scenario == "B":
                    self.send_json(200, effect)
                    gateway.trace.event("approval_response_sent", http_status=200, **effect)
                elif gateway.drop_requested.is_set():
                    gateway.response_dropped_at = gateway.trace.event("approval_response_dropped")
                    gateway.allow_lookup.set()
                    self.connection.shutdown(socket.SHUT_WR)

            def cancel(self):
                request = self.request_body()
                key = self.headers.get("Idempotency-Key")
                with gateway.lock:
                    gateway.cancels += 1
                    gateway.trace.event("cancel_received", request=request, idempotency_key=key)
                    approved = next((effect for effect in gateway.effects.values()
                                     if effect["paymentKey"] == PAYMENT_KEY), None)
                    if approved and key and key not in gateway.cancel_effects:
                        gateway.cancel_effects[key] = dict(approved, status="CANCELED", balanceAmount=0)
                        gateway.cancel_effect_time = gateway.trace.event(
                            "cancel_effect_committed", **gateway.cancel_effects[key]
                        )
                    gateway.canceled.set()
                # 첫 앱이 죽을 때까지 취소 응답을 보내지 않는다.
                gateway.finished.wait(120)

            def do_GET(self):
                with gateway.lock:
                    gateway.lookups += 1
                    boundary = {"A2": "after_response_drop", "B": "after_fault_removed"}.get(
                        gateway.scenario, "after_kill"
                    )
                    gateway.trace.event("lookup_received", path=self.path,
                                        **{boundary: gateway.allow_lookup.is_set()})
                if not gateway.allow_lookup.is_set():
                    self.send_error(503)
                    return
                with gateway.lock:
                    effects = gateway.cancel_effects or gateway.effects
                    matching = next((value for value in effects.values()
                                     if self.path in ("/v1/payments/" + value["paymentKey"],
                                                      "/v1/payments/orders/" + value["orderId"])), None)
                if matching is None:
                    if gateway.scenario == "A4":
                        self.send_json(404, {"code": "NOT_FOUND_PAYMENT", "message": "결제 기록 없음"})
                        gateway.trace.event("lookup_not_found", http_status=404, code="NOT_FOUND_PAYMENT")
                    else:
                        self.send_error(404)
                    return
                self.send_json(200, matching)
                gateway.trace.event("lookup_response_sent", status=matching["status"],
                                    balance_amount=matching.get("balanceAmount"))

        with ThreadingHTTPServer(("127.0.0.1", 0), Handler) as server:
            worker = threading.Thread(target=server.serve_forever, kwargs={"poll_interval": 0.05})
            worker.start()
            try:
                yield f"http://127.0.0.1:{server.server_port}"
            finally:
                self.finished.set()
                server.shutdown()
                worker.join()


@contextmanager
def app(args, db, gateway_url, secret, trace, phase, ddl, *, recovery_enabled=True):
    log_path = trace.directory / (phase + ".log")
    jvm_options = ["-XX:ActiveProcessorCount=2", "-Xmx512m"]
    if args.scenario == "A4" and ddl == "create":
        jvm_options.append("-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=127.0.0.1:0")
    order_options = []
    if args.a4_short_expiry:
        order_options = [f"--app.orders.reservation-ttl={SHORT_RESERVATION_TTL}s",
                         f"--app.orders.expiration-scan-delay={SHORT_EXPIRATION_SCAN}s"]
    with log_path.open("w") as log:
        process = subprocess.Popen(
            [args.java, *jvm_options, "-jar", str(args.jar),
             "--spring.config.location=classpath:/application.yml",
             "--server.port=0", "--server.address=127.0.0.1",
             f"--spring.datasource.url=jdbc:postgresql://127.0.0.1:{args.db_port}/{args.db_name}"
             f"?currentSchema={db.schema}",
             f"--spring.datasource.username={args.db_user}",
             f"--spring.jpa.hibernate.ddl-auto={ddl}", "--kakao.oauth.native-app-key=a1-test",
             f"--app.jwt.secret={secret}", f"--app.payments.recovery.enabled={str(recovery_enabled).lower()}",
             f"--app.payments.recovery.scan-delay={args.scan_seconds if ddl == 'validate' or args.scenario in ('A2', 'B') else 30}s",
             f"--app.toss-payments.read-timeout={READ_TIMEOUT}s",
             "--app.toss-payments.secret-key=a1-test", f"--app.toss-payments.base-url={gateway_url}",
             *order_options],
            stdout=log, stderr=subprocess.STDOUT, env={},
        )
        trace.event("app_started", phase=phase, pid=process.pid)
        try:
            deadline = time.monotonic() + 45
            while time.monotonic() < deadline and process.poll() is None:
                content = log_path.read_text()
                port = re.search(r"Tomcat started on port (\d+)", content)
                if port and "Started KitschcatchBackendApplication in " in content:
                    ready = trace.event("app_ready", phase=phase, pid=process.pid)
                    yield process, int(port[1]), ready
                    return
                time.sleep(0.1)
            raise RuntimeError(f"앱 시작 실패. 로그: {log_path}")
        finally:
            if process.poll() is None:
                process.kill()
                process.wait(timeout=10)


def run(args, directory):
    trace = Trace(directory)
    db = Database(args, args.scenario.lower() + "_" + uuid.uuid4().hex)
    secret = secrets.token_hex(32)
    pg = PaymentGateway(trace, args.scenario)
    db.query(f"CREATE SCHEMA {db.schema}")
    trace.event("schema_created", schema=db.schema)
    try:
        with pg.serve() as pg_url, ThreadPoolExecutor(max_workers=1) as client:
            with app(args, db, pg_url, secret, trace, "original", "create") as (first, port, _):
                seller, buyer, post_id = db.fixture()
                schema = db.query("""
                    SELECT indexdef FROM pg_indexes WHERE schemaname = current_schema()
                    UNION ALL
                    SELECT conname || ': ' || pg_get_constraintdef(oid) FROM pg_constraint
                    WHERE connamespace = current_schema()::regnamespace
                    ORDER BY 1
                """)
                (directory / "schema.txt").write_text(schema + "\n")
                bearer = token(buyer, secret)
                if args.scenario == "A4":
                    order, route, body, control_id = start_approval_and_kill(
                        args, trace, db, pg, first, client, port, seller, post_id, bearer
                    )
                else:
                    order = create_order(port, bearer, post_id)
                    route, body = approval_request(order)
                recipients = sorted([seller, buyer])
                if args.scenario == "B":
                    return recover_after_commit_rejection(args, trace, db, pg, first, client,
                                                          port, post_id, order, route, body, bearer,
                                                          recipients)
                if args.scenario == "A3":
                    cancel_id, sent = start_cancel_and_kill(trace, db, pg, first, client, port, post_id,
                                                            order, route, body, bearer, recipients)
                elif args.scenario != "A4":
                    pending = client.submit(post_response, port, route, body, bearer,
                                            202 if args.scenario == "A2" else 200)
                    require(pg.approved.wait(8), "PG 승인 요청에 도달하지 않았습니다.")
                    before = db.state(post_id)
                    trace.event("before_kill" if args.scenario == "A1" else "before_response_drop", **before)
                    expect_state(before, order, ("PROCESSING", "PENDING", "RESERVED", "PROCESSING"), [])
                    require(pg.approvals == 1 and len(pg.effects) == 1, "PG 승인 효과가 1회가 아닙니다.")
                    effect = next(iter(pg.effects.values()))
                    require(effect["orderId"] == order["pgOrderId"] and effect["paymentKey"] == PAYMENT_KEY
                            and effect["totalAmount"] == AMOUNT, "PG 승인 요청 식별자 또는 금액이 다릅니다.")
                    require(not pending.done() and first.poll() is None, "장애 주입 전에 승인 요청이 끝났습니다.")
                    if args.scenario == "A2":
                        return recover_after_response_loss(args, trace, db, pg, first, port, pending,
                                                           post_id, order, route, body, bearer, recipients)

                    sent = kill_during_request(trace, first, pending, pg.effect_time)
                    require(pg.lookups == 0, "종료 전에 원래 JVM이 조회를 시작했습니다.")
                    after_kill = db.state(post_id)
                    trace.event("after_kill", **after_kill)
                    expect_state(after_kill, order, ("PROCESSING", "PENDING", "RESERVED", "PROCESSING"), [])
                    pg.allow_lookup.set()
                    pg.finished.set()

            with app(args, db, pg_url, secret, trace, "restart", "validate") as (second, port, ready):
                require(second.pid != first.pid, "재기동 JVM의 PID가 바뀌지 않았습니다.")
                if args.scenario == "A4":
                    return observe_missing_approval(args, trace, db, pg, second, port, ready,
                                                    post_id, control_id, order, route, body, bearer)
                if args.scenario == "A3":
                    recovered = observe_cancellation(args.scan_seconds + 20, trace, db, second, pg,
                                                     port, post_id, order, cancel_id, bearer, recipients, secret)
                    return dict(pg_to_recovery_s=round(recovered - pg.cancel_effect_time, 3),
                                restart_ready_to_recovery_s=round(recovered - ready, 3),
                                effect_to_sigkill_s=round(sent - pg.cancel_effect_time, 3),
                                approval_posts=pg.approvals, approval_effects=len(pg.effects),
                                cancel_posts=pg.cancels, cancel_effects=len(pg.cancel_effects),
                                lookup_gets=pg.lookups)
                recovered = observe_recovery(args.scan_seconds + 20, trace, db, second, pg,
                                             port, post_id, order, route, body, bearer, recipients)
                return dict(pg_to_recovery_s=round(recovered - pg.effect_time, 3),
                            restart_ready_to_recovery_s=round(recovered - ready, 3),
                            effect_to_sigkill_s=round(sent - pg.effect_time, 3),
                            approval_posts=pg.approvals, approval_effects=len(pg.effects),
                            lookup_gets=pg.lookups)
    finally:
        db.query(f"DROP SCHEMA {db.schema} CASCADE")


def create_order(port, bearer, post_id):
    return post(port, "/api/orders", dict(postId=post_id, amount=AMOUNT, paymentMethod="CARD"),
                bearer, 201)


def approval_request(order):
    return f"/api/payments/{order['paymentId']}/confirm", dict(
        paymentId=order["paymentId"], paymentKey=PAYMENT_KEY, attemptId=order["attemptId"]
    )


def await_log(process, path, marker):
    deadline = time.monotonic() + 20
    while time.monotonic() < deadline and process.poll() is None:
        for line in path.read_text().splitlines():
            if line.startswith(marker):
                return line
        time.sleep(0.05)
    raise RuntimeError(f"{marker} 이벤트를 확인하지 못했습니다. 로그: {path}")


def start_approval_and_kill(args, trace, db, pg, process, client, port, seller, post_id, bearer):
    original_log = (trace.directory / "original.log").read_text()
    debug_port = re.search(r"Listening for transport dt_socket at address: (\d+)", original_log)
    require(debug_port is not None, "실험 JVM의 루프백 디버그 포트를 찾지 못했습니다.")
    path = trace.directory / "approval-breakpoint.log"
    with path.open("w") as log:
        debugger = subprocess.Popen(
            [args.java, "-XX:ActiveProcessorCount=2", "--add-modules", "jdk.jdi",
             "-cp", str(args.debugger_classes), "BeforePgApproval", debug_port[1]],
            stdout=log, stderr=subprocess.STDOUT, env={},
        )
        try:
            await_log(debugger, path, "ARMED")
            control_id = None
            if args.a4_short_expiry:
                control_id = db.new_post(seller)
                create_order(port, bearer, control_id)
            order = create_order(port, bearer, post_id)
            route, body = approval_request(order)
            pending = client.submit(post_response, port, route, body, bearer, 200)
            location = await_log(debugger, path, "PAUSED ")
            paused = trace.event("before_pg_approval", pid=process.pid, location=location)
            state = db.state(post_id)
            trace.event("before_kill", approval_posts=pg.approvals, approval_effects=len(pg.effects),
                        **state)
            expect_state(state, order, ("PROCESSING", "PENDING", "RESERVED", "PROCESSING"), [])
            require(pg.approvals == len(pg.effects) == pg.lookups == 0,
                    "종료 전에 PG 호출 또는 승인 효과가 관측됐습니다.")
            kill_during_request(trace, process, pending, paused)
            state = db.state(post_id)
            trace.event("after_kill", **state)
            expect_state(state, order, ("PROCESSING", "PENDING", "RESERVED", "PROCESSING"), [])
            pg.allow_lookup.set()
            return order, route, body, control_id
        finally:
            if debugger.poll() is None:
                debugger.terminate()
                debugger.wait(timeout=10)


def observe_missing_approval(args, trace, db, pg, process, port, ready, post_id, control_id,
                             order, route, body, bearer):
    min_checks = 2 if args.a4_short_expiry else 1
    deadline = time.monotonic() + args.scan_seconds + 35 + (35 if min_checks == 2 else 0)
    seen_checks = 0
    control_expired = False
    first_unknown = None
    while time.monotonic() < deadline and process.poll() is None:
        state = db.state(post_id)
        require(state["attempt"] in ("PROCESSING", "UNKNOWN"), "미확정 승인이 종료됐습니다.")
        expect_state(state, order, ("PROCESSING", "PENDING", "RESERVED", state["attempt"]), [])
        if state["attempt"] == "UNKNOWN" and state["check_count"] > seen_checks:
            seen_checks = state["check_count"]
            observed = trace.event("lookup_failure_saved", pid=process.pid, **state)
            if first_unknown is None:
                first_unknown = observed
        if control_id is not None and not control_expired:
            control = db.state(control_id)
            if control["order"] == "CANCELED":
                require(control["post"] == "ON_SALE" and control["owner"] is None
                        and control["reservation_expired"], "일반 예약의 만료 해제가 일치하지 않습니다.")
                trace.event("control_reservation_expired", **control)
                control_expired = True
        if seen_checks >= min_checks and (control_id is None or control_expired):
            break
        time.sleep(0.25)
    else:
        raise RuntimeError("예정한 조회 실패나 대조 예약의 만료를 관측하지 못했습니다.")

    require(state["recovery_state"] == "PENDING" and state["failure_reason"] == "토스페이먼츠 요청에 실패했습니다."
            and state["next_check_pending"] and state["lease_until"] is None
            and pg.lookups == state["check_count"], "조회 실패 뒤 재조회 상태가 일치하지 않습니다.")
    if control_id is not None:
        require(state["reservation_expired"], "대상 예약의 기한이 지나지 않았습니다.")
    status, payload = post_response(port, route, body, bearer, 202)
    require(payload["data"]["status"] == "PROCESSING", "동일 승인 요청이 PROCESSING을 반환하지 않았습니다.")
    trace.event("approval_replay_response", pid=process.pid, http_status=status, body=payload)
    final = db.state(post_id)
    trace.event("still_unresolved", pid=process.pid, approval_posts=pg.approvals,
                approval_effects=len(pg.effects), **final)
    expect_state(final, order, ("PROCESSING", "PENDING", "RESERVED", "UNKNOWN"), [])
    require(pg.approvals == len(pg.effects) == 0 and process.poll() is None,
            "승인 POST가 발생했거나 재기동 JVM이 종료됐습니다.")
    return dict(restart_ready_to_unknown_s=round(first_unknown - ready, 3),
                approval_posts=pg.approvals, approval_effects=len(pg.effects), lookup_gets=pg.lookups,
                check_count=final["check_count"], replay_http_status=status,
                reservation_expired=final["reservation_expired"], control_expired=control_expired)


def read_commit_rejection(db_log, offset, payment_id, trace):
    deadline = time.monotonic() + 5
    while time.monotonic() < deadline:
        with db_log.open("rb") as log:
            log.seek(offset)
            lines = log.read().decode(errors="replace").splitlines()
        for index, line in enumerate(lines):
            match = re.search(r"\[(\d+)\] ERROR:\s+P126B:\s+B_COMMIT_REJECTED (\{.*\})$", line)
            if not match:
                continue
            attempted = json.loads(match[2])
            if attempted["payment_id"] != payment_id:
                continue
            commit = re.compile(rf"\[{match[1]}\] STATEMENT:\s+COMMIT;?\s*$", re.IGNORECASE)
            following = lines[index + 1:]
            for following_index, candidate in enumerate(following):
                if commit.search(candidate):
                    evidence = [line, *(item for item in following[:following_index + 1]
                                        if f"[{match[1]}]" in item)]
                    (trace.directory / "postgres-commit.log").write_text("\n".join(evidence) + "\n")
                    return attempted
        time.sleep(0.05)
    raise RuntimeError("해당 결제의 PostgreSQL COMMIT 거절 로그를 찾지 못했습니다.")


def recover_after_commit_rejection(args, trace, db, pg, process, client, port, post_id, order,
                                   route, body, bearer, recipients):
    fault = (ROOT / "scripts/payment_recovery_b_fault.sql").read_text()
    db.psql("-v", f"payment_id={order['paymentId']}", "-f", "-", input=fault)
    trigger = db.query("""
        SELECT pg_get_triggerdef(oid) FROM pg_trigger
        WHERE tgrelid = 'payments'::regclass AND tgname = 'reject_b_recovery_commit'
          AND tgdeferrable AND tginitdeferred
    """)
    require(trigger and order["paymentId"] in trigger, "대상 결제의 지연 제약 트리거가 없습니다.")
    trace.event("fault_installed", payment_id=order["paymentId"], trigger=trigger, sqlstate="P126B")
    offset = args.b_postgres_log.stat().st_size
    pending = client.submit(post_response, port, route, body, bearer, 500)
    require(pg.approved.wait(8), "PG 승인 요청에 도달하지 않았습니다.")
    before = db.state(post_id)
    trace.event("before_approval_response", pid=process.pid, **before)
    expect_state(before, order, ("PROCESSING", "PENDING", "RESERVED", "PROCESSING"), [])
    require(pg.approvals == len(pg.effects) == 1 and not pending.done(),
            "승인 응답을 보류한 상태의 PG 효과가 일치하지 않습니다.")
    effect = next(iter(pg.effects.values()))
    require(effect["orderId"] == order["pgOrderId"] and effect["paymentKey"] == PAYMENT_KEY
            and effect["totalAmount"] == AMOUNT and effect["status"] == "DONE",
            "원래 결제에 해당하는 PG 승인 효과가 아닙니다.")
    pg.finished.set()

    status, payload = pending.result(timeout=15)
    trace.event("approval_client_response", pid=process.pid, http_status=status, body=payload)
    require(payload.get("error", {}).get("code") == "COMMON_999",
            "커밋 거절이 앱의 서버 오류 응답으로 전달되지 않았습니다.")
    attempted = read_commit_rejection(args.b_postgres_log, offset, order["paymentId"], trace)
    app_log = (trace.directory / "original.log").read_text()
    require("PgConnection.commit(" in app_log and "PaymentRecoveryService" in app_log,
            "앱의 복구 트랜잭션이 JDBC 커밋 중 실패한 근거를 찾지 못했습니다.")
    trace.event("db_commit_rejected", sqlstate="P126B", attempted=attempted)
    require(tuple(attempted[key] for key in ("payment", "order", "post", "attempt"))
            == ("SUCCESS", "PAID", "SOLD_OUT", "SUCCEEDED")
            and attempted["notifications"] == recipients and attempted["owner"] == order["orderId"]
            and attempted["attempt_id"] == order["attemptId"]
            and attempted["approved_at"] is not None and attempted["completed_at"] is not None,
            f"커밋 시점에 기대한 변경 전체를 관측하지 못했습니다. {attempted}")

    rolled_back = db.state(post_id)
    trace.event("rolled_back", pid=process.pid, **rolled_back)
    require(rolled_back == before and pg.lookups == 0 and process.poll() is None,
            "커밋 거절 후 초기 상태 외의 변경이 남았거나 워커가 먼저 개입했습니다.")
    db.query("""
        BEGIN;
        DROP TRIGGER reject_b_recovery_commit ON payments;
        DROP FUNCTION reject_b_recovery_commit();
        COMMIT;
    """)
    require(db.query("""
        SELECT count(*) FROM pg_trigger WHERE tgrelid = 'payments'::regclass
            AND tgname = 'reject_b_recovery_commit'
    """) == "0", "커밋 장애 트리거가 남아 있습니다.")
    removed = trace.event("fault_removed", pid=process.pid)
    pg.allow_lookup.set()

    recovered = observe_recovery(args.scan_seconds + 20, trace, db, process, pg, port, post_id,
                                 order, route, body, bearer, recipients)
    return dict(pg_to_recovery_s=round(recovered - pg.effect_time, 3),
                fault_removed_to_recovery_s=round(recovered - removed, 3),
                approval_http_status=status, sqlstate="P126B", approval_posts=pg.approvals,
                approval_effects=len(pg.effects), lookup_gets=pg.lookups)


def kill_during_request(trace, process, pending, effect_time):
    require(not pending.done() and process.poll() is None, "장애 주입 전에 요청이 끝났습니다.")
    sent = trace.event("sigkill_sent", pid=process.pid)
    require(sent - effect_time < READ_TIMEOUT, "PG 응답 제한 시간을 초과했습니다.")
    process.kill()
    require(process.wait(timeout=10) == -signal.SIGKILL, "SIGKILL 종료를 확인하지 못했습니다.")
    trace.event("app_killed", pid=process.pid)
    disconnected = pending.exception(timeout=10)
    require(isinstance(disconnected, (RemoteDisconnected, ConnectionResetError)),
            f"강제 종료에 따른 연결 단절을 확인하지 못했습니다. {disconnected!r}")
    return sent


def start_cancel_and_kill(trace, db, pg, process, client, port, post_id, order, route, body,
                          bearer, recipients):
    require(post(port, route, body, bearer, 200)["status"] == "SUCCESS", "준비 승인이 실패했습니다.")
    approved = db.state(post_id)
    trace.event("approved_before_cancel", **approved)
    expect_state(approved, order, ("SUCCESS", "PAID", "SOLD_OUT", "SUCCEEDED"), recipients)
    require(approved["shipment_registered_at"] is None and approved["cancel_attempts"] == 0,
            "배송 전 결제가 아닙니다.")
    pending = client.submit(post_response, port, f"/api/payments/{order['paymentId']}/cancel",
                            {}, bearer, 200)
    require(pg.canceled.wait(8), "PG 취소 요청에 도달하지 않았습니다.")
    effect = next(iter(pg.cancel_effects.values()), None)
    require(pg.cancels == len(pg.cancel_effects) == 1 and effect["status"] == "CANCELED"
            and effect["balanceAmount"] == 0 and effect["totalAmount"] == AMOUNT
            and effect["orderId"] == order["pgOrderId"] and effect["paymentKey"] == PAYMENT_KEY,
            "원래 승인에 해당하는 전액 취소 효과를 확인하지 못했습니다.")
    state = db.state(post_id)
    cancel_id = state["attempt_id"]
    trace.event("before_kill", **state)
    expect_cancel_state(state, order, cancel_id, recipients, recovered=False)
    sent = kill_during_request(trace, process, pending, pg.cancel_effect_time)
    require(pg.lookups == 0, "종료 전에 원래 JVM이 조회를 시작했습니다.")
    state = db.state(post_id)
    trace.event("after_kill", **state)
    expect_cancel_state(state, order, cancel_id, recipients, recovered=False)
    pg.allow_lookup.set()
    pg.finished.set()
    return cancel_id, sent


def expect_cancel_state(state, order, cancel_id, recipients, recovered):
    statuses = (("CANCELED", "CANCELED", "ON_SALE", "SUCCEEDED") if recovered
                else ("PROCESSING", "PAID", "SOLD_OUT", "PROCESSING"))
    owner = None if recovered else order["orderId"]
    require(tuple(state[key] for key in ("payment", "order", "post", "attempt")) == statuses
            and state["owner"] == owner and state["shipment_registered_at"] is None
            and state["order_id"] == order["orderId"] and state["payment_id"] == order["paymentId"]
            and state["attempt_id"] == cancel_id and cancel_id != order["attemptId"]
            and state["approved_confirm_attempt_id"] == order["attemptId"]
            and state["operation"] == "CANCEL" and state["attempts"] == 2 and state["cancel_attempts"] == 1
            and state["notifications"] == recipients
            and state["cancellation_notifications"] == (recipients if recovered else [])
            and state["pg_status"] == ("CANCELED" if recovered else None),
            f"취소 기대 상태 {statuses}, 예약 소유자 {owner}. 실제: {state}")


def observe_cancellation(timeout, trace, db, process, pg, port, post_id, order, cancel_id,
                         bearer, recipients, secret):
    state, recovered = await_recovery(timeout, trace, db, process, post_id, "CANCELED")
    expect_cancel_state(state, order, cancel_id, recipients, recovered=True)
    require(pg.lookups >= 1 and pg.cancels == 1, "재기동 후 조회로 취소를 확정하지 못했습니다.")
    status, payload = post_response(port, f"/api/payments/{order['paymentId']}/cancel", {}, bearer, 200)
    require(payload["data"]["status"] == "CANCELED", "취소 재전송이 CANCELED를 반환하지 않았습니다.")
    replay = db.state(post_id)
    trace.event("after_replay", pid=process.pid, http_status=status, **replay)
    expect_cancel_state(replay, order, cancel_id, recipients, recovered=True)
    require(pg.approvals == len(pg.effects) == pg.cancels == len(pg.cancel_effects) == 1,
            "승인 또는 취소 요청이 PG에 추가로 전달됐습니다.")

    # 상태값 확인에 이어 다른 구매자가 실제 주문 API로 같은 상품을 예약한다.
    new_buyer = int(db.query("""
        INSERT INTO users (nickname, auth_provider, provider_user_id, created_at)
        VALUES ('new-buyer', 'KAKAO', 'new-buyer', now()) RETURNING id
    """))
    replacement = post(port, "/api/orders", dict(postId=post_id, amount=AMOUNT, paymentMethod="CARD"),
                       token(new_buyer, secret), 201)
    reserved = db.state(post_id)
    trace.event("new_buyer_reserved", pid=process.pid, http_status=201, **reserved)
    require(process.poll() is None and reserved["post"] == "RESERVED" and reserved["order"] == "PENDING"
            and reserved["payment"] == "READY"
            and reserved["order_id"] == reserved["owner"] == replacement["orderId"] != order["orderId"],
            "복구 후 새 주문이 상품 예약을 소유하지 못했습니다.")
    return recovered


def recover_after_response_loss(args, trace, db, pg, process, port, pending,
                                post_id, order, route, body, bearer, recipients):
    pg.drop_requested.set()
    pg.finished.set()
    status, payload = pending.result(timeout=READ_TIMEOUT + 5)
    accepted = trace.event("approval_client_response", pid=process.pid, http_status=status, body=payload)
    require(pg.response_dropped_at is not None
            and pg.response_dropped_at - pg.effect_time < READ_TIMEOUT,
            "읽기 제한 전에 PG 승인 응답을 끊지 못했습니다.")
    require(payload["data"]["status"] == "PROCESSING" and process.poll() is None,
            "동일 JVM의 승인 응답이 PROCESSING이 아닙니다.")
    unknown = db.state(post_id)
    trace.event("after_response_drop", pid=process.pid, **unknown)
    expect_state(unknown, order, ("PROCESSING", "PENDING", "RESERVED", "UNKNOWN"), [])
    require(unknown["recovery_state"] == "PENDING" and unknown["failure_reason"] == "TRANSPORT_ERROR"
            and unknown["next_check_at"] is not None, "전송 오류에 대한 자동 조회가 예약되지 않았습니다.")

    replay_status, replay_payload = post_response(port, route, body, bearer, 202)
    trace.event("processing_replay_response", pid=process.pid, http_status=replay_status,
                body=replay_payload)
    require(replay_payload["data"]["status"] == "PROCESSING"
            and pg.approvals == 1 and pg.lookups == 0, "미확정 상태의 재전송이 PG를 호출했습니다.")

    # 전송 오류의 최초 재조회 대기 30초와 스캔 주기를 모두 기다린다.
    recovered = observe_recovery(30 + args.scan_seconds + 20, trace, db, process, pg,
                                 port, post_id, order, route, body, bearer, recipients)
    return dict(pg_to_recovery_s=round(recovered - pg.effect_time, 3),
                accepted_to_recovery_s=round(recovered - accepted, 3),
                drop_to_accepted_s=round(accepted - pg.response_dropped_at, 3),
                approval_http_status=status, processing_replay_http_status=replay_status,
                approval_posts=pg.approvals, approval_effects=len(pg.effects), lookup_gets=pg.lookups)


def await_recovery(timeout, trace, db, process, post_id, target_status):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline and process.poll() is None:
        state = db.state(post_id)
        if state["payment"] == target_status:
            return state, trace.event("recovered", pid=process.pid, **state)
        time.sleep(0.25)
    raise RuntimeError("스케줄러의 자동 복구를 관측하지 못했습니다.")


def observe_recovery(timeout, trace, db, process, pg, port, post_id, order, route, body, bearer,
                     recipients):
    state, recovered = await_recovery(timeout, trace, db, process, post_id, "SUCCESS")
    expected = ("SUCCESS", "PAID", "SOLD_OUT", "SUCCEEDED")
    expect_state(state, order, expected, recipients)
    require(pg.lookups >= 1, "PG 조회 없이 완료됐습니다.")
    status, payload = post_response(port, route, body, bearer, 200)
    require(payload["data"]["status"] == "SUCCESS", "재전송에서 SUCCESS 응답을 받지 못했습니다.")
    replay = db.state(post_id)
    trace.event("after_replay", pid=process.pid, http_status=status, **replay)
    expect_state(replay, order, expected, recipients)
    require(process.poll() is None and pg.approvals == len(pg.effects) == 1,
            "복구를 처리한 JVM이 종료됐거나 PG 승인이 다시 호출됐습니다.")
    return recovered


def expect_state(state, order, statuses, notifications):
    require(
        tuple(state[key] for key in ("payment", "order", "post", "attempt")) == statuses
        and state["owner"] == state["order_id"] == order["orderId"]
        and state["payment_id"] == order["paymentId"] and state["attempt_id"] == order["attemptId"]
        and state["attempts"] == 1 and state["operation"] == "CONFIRM"
        and state["notifications"] == notifications,
        f"기대 상태 {statuses}, 알림 {notifications}. 실제: {state}",
    )


def main():
    parser = argparse.ArgumentParser(description="PG 승인·취소 중 장애 이후 자동 결제 복구를 검증한다.")
    parser.add_argument("--scenario", choices=("A1", "A2", "A3", "A4", "B"), default="A1")
    parser.add_argument("--jar", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True, help="새 결과 디렉터리")
    parser.add_argument("--java", default="java")
    parser.add_argument("--db-port", type=int, required=True, help="격리 로컬 PostgreSQL 포트")
    parser.add_argument("--db-user", required=True)
    parser.add_argument("--db-name", required=True)
    parser.add_argument("--repeat", type=int, default=1)
    parser.add_argument("--scan-seconds", type=int, default=30)
    parser.add_argument("--a4-short-expiry", action="store_true",
                        help="A4에서 15초 예약과 2초 만료 스캔으로 대조군을 확인한다.")
    parser.add_argument("--b-postgres-log", type=Path,
                        help="B의 COMMIT 거절을 확인할 실험 전용 PostgreSQL 서버 로그")
    args = parser.parse_args()
    require(args.jar.is_file() and args.repeat > 0 and args.scan_seconds > 0, "실행 인자를 확인하세요.")
    # JDBC URL에 들어가는 값은 격리 실험의 단순 식별자만 허용한다.
    require(re.fullmatch(r"[a-zA-Z_][a-zA-Z_0-9]*", args.db_name), "DB 이름을 확인하세요.")
    args.psql = shutil.which("psql")
    require(args.psql is not None, "psql을 PATH에 추가하세요.")
    require(not args.a4_short_expiry or args.scenario == "A4", "단축 만료는 A4 전용입니다.")
    require(args.scenario != "B" or (args.b_postgres_log is not None and args.b_postgres_log.is_file()),
            "B에는 읽을 수 있는 실험 전용 PostgreSQL 로그가 필요합니다.")
    args.out.mkdir(parents=True)
    if args.scenario == "A4":
        args.debugger_classes = args.out / "debugger"
        args.debugger_classes.mkdir()
        java = Path(shutil.which(args.java)).resolve()
        command(str(java.with_name("javac")), "--add-modules", "jdk.jdi",
                "-d", str(args.debugger_classes), str(ROOT / "scripts/BeforePgApproval.java"))
    with args.jar.open("rb") as jar:
        jar_sha = hashlib.file_digest(jar, "sha256").hexdigest()
    manifest = dict(scenario=args.scenario, checkout_commit=command("git", "rev-parse", "HEAD", cwd=ROOT),
                    jar_sha256=jar_sha,
                    runner_sha256=hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
                    java=subprocess.run([args.java, "-version"], capture_output=True,
                                        text=True, check=True, timeout=15).stderr,
                    postgres=Database(args, "public").query("SHOW server_version"),
                    recovery_enabled=True, batch=100, lease_seconds=45, read_timeout_seconds=READ_TIMEOUT,
                    original_scan_seconds=args.scan_seconds if args.scenario in ("A2", "B") else 30,
                    restart_scan_seconds=args.scan_seconds if args.scenario not in ("A2", "B") else None,
                    reservation_ttl_seconds=SHORT_RESERVATION_TTL if args.a4_short_expiry else 900,
                    expiration_scan_seconds=SHORT_EXPIRATION_SCAN if args.a4_short_expiry else 60,
                    debugger_sha256=hashlib.sha256((ROOT / "scripts/BeforePgApproval.java").read_bytes()).hexdigest()
                    if args.scenario == "A4" else None,
                    fault_sql_sha256=hashlib.sha256((ROOT / "scripts/payment_recovery_b_fault.sql").read_bytes()).hexdigest()
                    if args.scenario == "B" else None,
                    repeat=args.repeat, amount=AMOUNT, poll_interval_seconds=0.25)
    (args.out / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
    fields = ["run", "result", "pg_to_recovery_s", "approval_posts", "approval_effects", "lookup_gets"]
    if args.scenario == "A2":
        fields += ["accepted_to_recovery_s", "drop_to_accepted_s", "approval_http_status",
                   "processing_replay_http_status"]
    elif args.scenario == "B":
        fields += ["fault_removed_to_recovery_s", "approval_http_status", "sqlstate"]
    elif args.scenario == "A4":
        fields = ["run", "result", "restart_ready_to_unknown_s", "approval_posts", "approval_effects",
                  "lookup_gets", "check_count", "replay_http_status", "reservation_expired", "control_expired"]
    else:
        fields += ["restart_ready_to_recovery_s", "effect_to_sigkill_s"]
    if args.scenario == "A3":
        fields += ["cancel_posts", "cancel_effects"]
    with (args.out / "scenario-results.csv").open("w", newline="") as output:
        writer = csv.DictWriter(output, fieldnames=fields)
        writer.writeheader()
        for iteration in range(1, args.repeat + 1):
            directory = args.out / f"run-{iteration:02d}"
            directory.mkdir()
            try:
                result = run(args, directory)
            except Exception:
                writer.writerow(dict(run=iteration, result="fail"))
                raise
            writer.writerow(dict(run=iteration, result="pass", **result))
            output.flush()
            print(f"{args.scenario} {iteration}/{args.repeat}: {result}", flush=True)


if __name__ == "__main__":
    main()
