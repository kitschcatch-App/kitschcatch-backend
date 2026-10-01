-- 반품 검수와 철회 기록을 추가하고 기존 환불 상태를 보존한다.
BEGIN;
LOCK TABLE orders IN SHARE ROW EXCLUSIVE MODE;
ALTER TABLE orders ADD COLUMN IF NOT EXISTS refund_reviewed_by BIGINT;
ALTER TABLE orders ADD COLUMN IF NOT EXISTS refund_reviewed_at TIMESTAMP;
ALTER TABLE orders ADD COLUMN IF NOT EXISTS refund_review_reason VARCHAR(200);
ALTER TABLE orders ADD COLUMN IF NOT EXISTS refund_return_received BOOLEAN;
ALTER TABLE orders ADD COLUMN IF NOT EXISTS refund_withdrawn_at TIMESTAMP;
ALTER TABLE orders DROP CONSTRAINT IF EXISTS orders_refund_status_check;
ALTER TABLE orders DROP CONSTRAINT IF EXISTS ck_orders_refund_status;
ALTER TABLE orders ADD CONSTRAINT ck_orders_refund_status CHECK
    (refund_status IN ('REQUESTED','PROCESSING','COMPLETED','REJECTED','WITHDRAWN'));
COMMIT;
