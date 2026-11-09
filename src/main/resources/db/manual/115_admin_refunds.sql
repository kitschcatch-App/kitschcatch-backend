-- 환불 심사 대기열의 상태와 요청 시각 조회를 지원한다.
CREATE INDEX IF NOT EXISTS ix_orders_refund_queue
    ON orders (refund_status, refund_requested_at, id) WHERE refund_id IS NOT NULL;
