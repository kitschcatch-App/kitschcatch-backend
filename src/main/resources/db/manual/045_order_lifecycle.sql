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
COMMIT;
