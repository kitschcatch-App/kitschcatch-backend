-- 지급 요청 이후 결과 조회를 재시작 가능한 작업으로 관리한다.
BEGIN;
CREATE TABLE IF NOT EXISTS settlement_sync_jobs (
    settlement_id varchar(50) PRIMARY KEY REFERENCES orders(settlement_id) ON DELETE CASCADE,
    next_check_at timestamptz NOT NULL,
    attempts integer NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    lease_token uuid,
    lease_until timestamptz,
    last_checked_at timestamptz,
    state varchar(20) NOT NULL DEFAULT 'QUEUED' CHECK (state IN ('QUEUED','DONE','REVIEW_REQUIRED')),
    last_error varchar(30),
    CHECK ((lease_token IS NULL) = (lease_until IS NULL))
);
CREATE INDEX IF NOT EXISTS ix_settlement_sync_due
    ON settlement_sync_jobs(next_check_at, settlement_id) WHERE state='QUEUED';
INSERT INTO settlement_sync_jobs(settlement_id, next_check_at)
SELECT settlement_id, now() FROM orders
WHERE settlement_status IN ('PROCESSING','UNKNOWN') AND settlement_id IS NOT NULL
ON CONFLICT DO NOTHING;
COMMIT;
