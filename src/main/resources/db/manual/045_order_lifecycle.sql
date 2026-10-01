-- 주문 후속 처리의 이력 필드를 기존 주문을 보존하며 추가한다.
BEGIN;
LOCK TABLE orders IN SHARE ROW EXCLUSIVE MODE;
ALTER TABLE orders ADD COLUMN IF NOT EXISTS cancel_reason VARCHAR(200);
ALTER TABLE orders ADD COLUMN IF NOT EXISTS canceled_at TIMESTAMP;
UPDATE orders o SET canceled_at = p.canceled_at FROM payments p
WHERE p.order_id = o.id AND o.order_status = 'CANCELED' AND o.canceled_at IS NULL AND p.canceled_at IS NOT NULL;
ALTER TABLE orders ADD COLUMN IF NOT EXISTS shipment_carrier_code VARCHAR(30);
ALTER TABLE orders ADD COLUMN IF NOT EXISTS shipment_tracking_number VARCHAR(40);
ALTER TABLE orders ADD COLUMN IF NOT EXISTS shipment_registered_at TIMESTAMP;
ALTER TABLE orders ADD COLUMN IF NOT EXISTS shipment_updated_at TIMESTAMP;
ALTER TABLE orders ADD COLUMN IF NOT EXISTS refund_id VARCHAR(50);
ALTER TABLE orders ADD COLUMN IF NOT EXISTS refund_amount BIGINT;
ALTER TABLE orders ADD COLUMN IF NOT EXISTS refund_reason VARCHAR(200);
ALTER TABLE orders ADD COLUMN IF NOT EXISTS refund_status VARCHAR(30);
ALTER TABLE orders ADD COLUMN IF NOT EXISTS refund_requested_at TIMESTAMP;
ALTER TABLE orders ADD COLUMN IF NOT EXISTS refund_completed_at TIMESTAMP;
CREATE UNIQUE INDEX IF NOT EXISTS uk_orders_refund_id ON orders(refund_id);
ALTER TABLE orders ADD COLUMN IF NOT EXISTS confirmed_at TIMESTAMP;
DO $$ DECLARE c RECORD; BEGIN
    FOR c IN SELECT conname FROM pg_constraint WHERE conrelid='orders'::regclass AND contype='c'
        AND conname IN ('orders_order_status_check','ck_orders_order_status') LOOP
        EXECUTE format('ALTER TABLE orders DROP CONSTRAINT %I',c.conname);
    END LOOP;
END $$;
ALTER TABLE orders ADD CONSTRAINT ck_orders_order_status CHECK
    (order_status IN ('PENDING','PAID','CANCELED','REFUNDED','PURCHASE_CONFIRMED'));
ALTER TABLE orders ADD COLUMN IF NOT EXISTS settlement_id VARCHAR(50);
ALTER TABLE orders ADD COLUMN IF NOT EXISTS settlement_amount BIGINT;
ALTER TABLE orders ADD COLUMN IF NOT EXISTS settlement_fee BIGINT;
ALTER TABLE orders ADD COLUMN IF NOT EXISTS settlement_status VARCHAR(30);
ALTER TABLE orders ADD COLUMN IF NOT EXISTS settlement_requested_at TIMESTAMP;
ALTER TABLE orders ADD COLUMN IF NOT EXISTS settlement_settled_at TIMESTAMP;
ALTER TABLE orders ADD COLUMN IF NOT EXISTS settlement_provider_reference VARCHAR(200);
CREATE UNIQUE INDEX IF NOT EXISTS uk_orders_settlement_id ON orders(settlement_id);
COMMIT;
