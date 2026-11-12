#!/usr/bin/env python3
# 장기 미확정 결제의 예약 점유, 복구 지연과 PG 조회 비용을 측정한다.

import argparse
import csv
import hashlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
from pathlib import Path
import secrets
import shutil
import statistics
import subprocess
import threading
import time
from urllib.parse import unquote
import uuid

from payment_recovery_a1 import (
    AMOUNT, ROOT, Database, Trace, app, command, create_order, post_response, require, token,
)

PROFILES = {
    "persistent": (["unavailable"], 1020, 2),
    "delayed": (["delay-120"] * 3 + ["delay-600"] * 3 + ["unavailable"] * 4, 1020, 2),
    "fast100": (["ready"] * 100, 120, 1),
    "mixed100": (["timeout"] * 5 + ["ready"] * 95, 120, 1),
}


class Gateway(ThreadingHTTPServer):
    def __init__(self, trace, cases):
        super().__init__(("127.0.0.1", 0), Handler)
        self.trace = trace
        self.cases = cases
        self.lock = threading.Lock()
        self.effects = {}
        self.approvals = []
        self.lookups = []


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *_):
        pass

    def do_POST(self):
        pg = self.server
        require(self.path == "/v1/payments/confirm", "실험 범위 밖 PG 요청입니다.")
        if self.headers.get("Transfer-Encoding") == "chunked":
            chunks = []
            while size := int(self.rfile.readline().split(b";", 1)[0], 16):
                chunks.append(self.rfile.read(size))
                require(self.rfile.read(2) == b"\r\n", "잘못된 HTTP 청크입니다.")
            request = json.loads(b"".join(chunks))
        else:
            request = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
        key = request["paymentKey"]
        with pg.lock:
            case = pg.cases[key]
            require(case["pg_order_id"] == request["orderId"] and request["amount"] == AMOUNT,
                    "다른 거래의 승인 요청입니다.")
            received = pg.trace.event("approval_received", key=key)
            pg.approvals.append((key, received))
            if key not in pg.effects:
                committed = pg.trace.event("approval_effect_committed", key=key, status="DONE")
                delay = case["delay"]
                pg.effects[key] = {
                    "seconds": committed,
                    "available": None if delay is None else committed + delay,
                    "body": dict(paymentKey=key, orderId=request["orderId"], totalAmount=AMOUNT,
                                 status="DONE"),
                }
        # 승인 효과는 보존한 채 응답 없이 연결을 닫아 앱에 미확정 상태를 만든다.

    def do_GET(self):
        pg = self.server
        key = unquote(self.path.removeprefix("/v1/payments/"))
        with pg.lock:
            effect = pg.effects[key]
            case = pg.cases[key]
            started = pg.trace.event("lookup_started", key=key)
            record = dict(key=key, start=started)
            pg.lookups.append(record)
        if case["mode"] == "timeout":
            self.connection.settimeout(12)
            try:
                self.connection.recv(1)
            except (TimeoutError, OSError):
                pass
            outcome = "no_response"
        elif effect["available"] is None or started < effect["available"]:
            self.send_result(503, {"code": "UNKNOWN_PAYMENT_ERROR"})
            outcome = "503"
        else:
            self.send_result(200, effect["body"])
            outcome = "200"
        with pg.lock:
            record["end"] = pg.trace.event("lookup_finished", key=key, outcome=outcome)
            record["outcome"] = outcome

    def send_result(self, status, payload):
        body = json.dumps(payload).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)


def seed_accounts_and_products(db, count):
    return json.loads(db.query(f"""
        WITH sellers AS (
            INSERT INTO users(nickname, auth_provider, provider_user_id, created_at)
            SELECT 'seller-' || i, 'KAKAO', 'seller-' || i, now()
            FROM generate_series(1, {count}) AS i RETURNING id, nickname
        ), buyers AS (
            INSERT INTO users(nickname, auth_provider, provider_user_id, created_at)
            SELECT 'buyer-' || i, 'KAKAO', 'buyer-' || i, now()
            FROM generate_series(1, {count}) AS i RETURNING id, nickname
        ), products AS (
            INSERT INTO posts(user_id, title, description, price, product_category, product_condition,
                              product_status, created_at, updated_at)
            SELECT id, nickname, 'C fixture', {AMOUNT}, 'GOODS', 'NEW', 'ON_SALE', now(), now()
            FROM sellers RETURNING id, title
        )
        SELECT json_agg(json_build_object('post_id', p.id, 'buyer_id', b.id)
                        ORDER BY split_part(p.title, '-', 2)::integer)
        FROM products p JOIN buyers b ON b.nickname = replace(p.title, 'seller-', 'buyer-')
    """))


def snapshot(db):
    return json.loads(db.query("""
        SELECT json_agg(json_build_object(
            'payment_id', p.payment_id, 'payment', p.payment_status, 'order', o.order_status,
            'post', s.product_status, 'order_id', o.order_number, 'owner', s.active_order_number,
            'attempt', a.attempt_status, 'attempt_id', a.attempt_id, 'recovery', p.recovery_state,
            'check_count', a.check_count, 'next_check_at', a.next_check_at,
            'lease_until', a.lease_until, 'failure_reason', a.failure_reason,
            'reservation_age_s', extract(epoch FROM localtimestamp - o.created_at),
            'expired', o.reservation_expires_at <= localtimestamp,
            'due_wait_s', CASE WHEN a.next_check_at <= localtimestamp AND
                (a.lease_until IS NULL OR a.lease_until <= localtimestamp)
                AND a.attempt_status IN ('PROCESSING','UNKNOWN')
                THEN extract(epoch FROM localtimestamp - a.next_check_at) END,
            'notifications', (SELECT count(*) FROM notifications n
                              WHERE n.target_id = o.order_number AND n.type = 'PAYMENT_SUCCESS')
        ) ORDER BY o.id)
        FROM orders o JOIN posts s ON s.id = o.post_id
        JOIN payments p ON p.order_id = o.id
        JOIN payment_attempts a ON a.payment_id = p.id AND a.attempt_id = p.current_attempt_id
    """))


def write_csv(path, rows):
    with path.open("w", newline="") as file:
        writer = csv.DictWriter(file, fieldnames=list(rows[0]))
        writer.writeheader()
        writer.writerows(rows)


def setup(args, db, gateway, trace, secret, modes, gateway_url):
    control = args.profile == "persistent"
    with app(args, db, gateway_url, secret, trace, "setup", "create", recovery_enabled=False) as (_, port, _):
        db.install_attempt_indexes()
        products = seed_accounts_and_products(db, len(modes) + int(control))
        orders = [create_order(port, token(row["buyer_id"], secret), row["post_id"])
                  for row in products]
        for index, (mode, row, order) in enumerate(zip(modes, products, orders)):
            key = f"c-{index + 1}"
            delay = 0 if mode == "ready" else int(mode.removeprefix("delay-")) if mode.startswith("delay-") else None
            gateway.cases[key] = dict(mode=mode, delay=delay, payment_id=order["paymentId"],
                                      pg_order_id=order["pgOrderId"], order_id=order["orderId"],
                                      attempt_id=order["attemptId"])
            route = f"/api/payments/{order['paymentId']}/confirm"
            body = dict(paymentId=order["paymentId"], paymentKey=key, attemptId=order["attemptId"])
            status, response = post_response(port, route, body, token(row["buyer_id"], secret), 202)
            require(status == 202 and response["data"]["status"] == "PROCESSING",
                    f"{key}: 최초 승인 결과가 미확정 상태가 아닙니다.")
        require(len(gateway.approvals) == len(gateway.effects) == len(modes) and not gateway.lookups,
                "관측 전에 승인 또는 조회 횟수가 일치하지 않습니다.")
    return orders[-1]["paymentId"] if control else None


def check_case(state, case):
    require(state["order_id"] == case["order_id"] and state["owner"] == case["order_id"]
            and state["attempt_id"] == case["attempt_id"], "예약 소유권이나 승인 시도가 바뀌었습니다.")
    if state["payment"] == "SUCCESS":
        require((state["order"], state["post"], state["attempt"], state["notifications"])
                == ("PAID", "SOLD_OUT", "SUCCEEDED", 2), "확정된 결제의 상태 또는 알림이 일치하지 않습니다.")
    else:
        require((state["payment"], state["order"], state["post"], state["notifications"])
                == ("PROCESSING", "PENDING", "RESERVED", 0)
                and state["attempt"] in ("PROCESSING", "UNKNOWN"),
                "미확정 결제의 예약 또는 내부 상태가 일치하지 않습니다.")


def observe(args, db, gateway, trace, secret, gateway_url, control_id, seconds, interval):
    by_payment = {case["payment_id"]: (key, case) for key, case in gateway.cases.items()}
    changes, completed, backlog = {}, {}, []
    control_released = None
    with app(args, db, gateway_url, secret, trace, "observe", "validate") as (process, _, ready):
        end = trace.start + ready + seconds
        next_probe = time.monotonic()
        while True:
            require(process.poll() is None, "관측 중 앱이 종료됐습니다.")
            states = snapshot(db)
            require(len(states) == len(by_payment) + int(control_id is not None),
                    "관측한 주문과 승인 시도의 수가 일치하지 않습니다.")
            observed = round(time.monotonic() - trace.start, 6)
            due = []
            for state in states:
                payment = state["payment_id"]
                if payment == control_id:
                    if state["payment"] == "CANCELED" and control_released is None:
                        require(state["expired"] and state["post"] == "ON_SALE" and state["owner"] is None,
                                "대조 예약 해제 상태가 일치하지 않습니다.")
                        control_released = observed
                        trace.event("control_released", **state)
                    continue
                key, case = by_payment[payment]
                check_case(state, case)
                signature = tuple(state[name] for name in (
                    "payment", "attempt", "check_count", "lease_until", "recovery", "expired",
                ))
                if changes.get(key) != signature:
                    trace.event("state_changed", key=key, mode=case["mode"], **state)
                    changes[key] = signature
                if state["payment"] == "SUCCESS":
                    completed.setdefault(key, observed)
                if state["due_wait_s"] is not None:
                    due.append(float(state["due_wait_s"]))
            backlog.append(dict(elapsed_s=round(observed - ready, 3), due_count=len(due),
                                oldest_due_wait_s=round(max(due, default=0), 3),
                                unresolved=len(by_payment) - len(completed)))
            if time.monotonic() >= end:
                break
            next_probe += interval
            time.sleep(max(0, min(next_probe, end) - time.monotonic()))

        rows = []
        with gateway.lock:
            attempts = list(gateway.lookups)
            approvals = list(gateway.approvals)
        require(len(approvals) == len(gateway.effects) == len(by_payment), "PG 승인 POST가 다시 발생했습니다.")
        for state in states:
            payment = state["payment_id"]
            if payment == control_id:
                continue
            key, case = by_payment[payment]
            effect = gateway.effects[key]
            done = completed.get(key)
            attempts_for_key = [attempt for attempt in attempts if attempt["key"] == key
                                and attempt["start"] <= observed]
            rows.append(dict(
                key=key, mode=case["mode"], payment=state["payment"], order=state["order"], post=state["post"],
                attempt=state["attempt"], recovery=state["recovery"], expired=state["expired"],
                unresolved=done is None, unresolved_occupancy_s=round((done or observed) - effect["seconds"], 3),
                held_at_end_s=round(float(state["reservation_age_s"]), 3) if done is None else None,
                lookup_available_s=effect["available"],
                recovery_delay_s=round(done - effect["available"], 3)
                if done is not None and effect["available"] is not None else None,
                gets=len(attempts_for_key), successful_gets=sum(a.get("outcome") == "200" for a in attempts_for_key),
                failed_503=sum(a.get("outcome") == "503" for a in attempts_for_key),
                unanswered_gets=sum(a.get("outcome") == "no_response" for a in attempts_for_key),
                check_count=state["check_count"], next_check_at=state["next_check_at"],
            ))
    write_csv(trace.directory / "scenario-results.csv", rows)
    write_csv(trace.directory / "backlog.csv", backlog)
    finish(args, trace, rows, backlog, attempts, ready, observed, control_released)


def finish(args, trace, rows, backlog, attempts, ready, observed, control_released):
    window = observed - ready
    gets = sum(row["gets"] for row in rows)
    unresolved = [row for row in rows if row["unresolved"]]
    active, max_active = 0, 0
    edges = []
    for request in attempts:
        if request["start"] <= observed:
            edges.append((request["start"], 1))
            edges.append((min(request.get("end", observed), observed), -1))
    for _, delta in sorted(edges):
        active += delta
        max_active = max(max_active, active)
    summary = dict(
        profile=args.profile, candidates=len(rows), observation_s=round(window, 3),
        resolved=len(rows) - len(unresolved), unresolved=len(unresolved),
        expired_unresolved=sum(row["expired"] for row in unresolved),
        review_required=sum(row["recovery"] == "REVIEW_REQUIRED" for row in rows),
        unresolved_reservation_minutes=round(sum(row["unresolved_occupancy_s"] for row in rows) / 60, 3),
        gets=gets, max_concurrent_stub_gets=max_active, gets_per_minute=round(gets * 60 / window, 3),
        max_due_count=max(row["due_count"] for row in backlog),
        max_due_wait_s=max(row["oldest_due_wait_s"] for row in backlog),
        control_released_after_ready_s=round(control_released - ready, 3) if control_released else None,
    )
    delays = [row["recovery_delay_s"] for row in rows if row["recovery_delay_s"] is not None]
    if delays:
        summary["recovery_delay_s"] = dict(min=min(delays), median=round(statistics.median(delays), 3),
                                           max=max(delays))
    for outcome in ("503", "200", "no_response"):
        durations = [a["end"] - a["start"] for a in attempts
                     if a.get("outcome") == outcome and a["start"] <= observed and a["end"] <= observed]
        if durations:
            summary[f"pg_{outcome}_seconds"] = round(sum(durations), 3)
    (trace.directory / "summary.json").write_text(json.dumps(summary, indent=2) + "\n")
    print(json.dumps(summary, indent=2), flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--profile", choices=PROFILES, required=True)
    parser.add_argument("--jar", type=Path, required=True)
    parser.add_argument("--java", default="java")
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--db-port", type=int, required=True)
    parser.add_argument("--db-user", required=True)
    parser.add_argument("--db-name", required=True)
    args = parser.parse_args()
    require(args.jar.is_file() and args.db_name.isidentifier(), "JAR 또는 실험 DB 이름을 확인하세요.")
    args.psql = shutil.which("psql")
    require(args.psql is not None, "psql을 PATH에 추가하세요.")
    args.scenario, args.scan_seconds, args.a4_short_expiry = "C", 30, False
    modes, seconds, interval = PROFILES[args.profile]
    args.out.mkdir(parents=True)
    trace = Trace(args.out)
    db = Database(args, "c_" + uuid.uuid4().hex)
    secret = secrets.token_hex(32)
    with args.jar.open("rb") as jar:
        manifest = dict(profile=args.profile, modes=modes, observation_s=seconds, poll_interval_s=interval,
                        checkout_commit=command("git", "rev-parse", "HEAD", cwd=ROOT),
                        jar_sha256=hashlib.file_digest(jar, "sha256").hexdigest(),
                        runner_sha256=hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
                        postgres=Database(args, "public").query("SHOW server_version"),
                        java=subprocess.run([args.java, "-version"], capture_output=True, text=True,
                                            check=True, timeout=15).stderr,
                        shared_runner_sha256=hashlib.sha256((ROOT / "scripts/payment_recovery_a1.py").read_bytes()).hexdigest(),
                        recovery_scan_s=30, batch_size=100, reservation_ttl_s=900, expiration_scan_s=60,
                        read_timeout_s=10, setup_recovery_enabled=False, observe_recovery_enabled=True)
    (args.out / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
    db.query(f"CREATE SCHEMA {db.schema}")
    try:
        with Gateway(trace, {}) as gateway:
            thread = threading.Thread(target=gateway.serve_forever, kwargs={"poll_interval": 0.05})
            thread.start()
            try:
                gateway_url = f"http://127.0.0.1:{gateway.server_port}"
                control_id = setup(args, db, gateway, trace, secret, modes, gateway_url)
                observe(args, db, gateway, trace, secret, gateway_url, control_id, seconds, interval)
            finally:
                gateway.shutdown()
                thread.join()
    finally:
        db.query(f"DROP SCHEMA {db.schema} CASCADE")


if __name__ == "__main__":
    main()
