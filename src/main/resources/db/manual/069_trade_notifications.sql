-- 기존 알림함의 거래 알림 종류와 송장 변경 번호를 확장한다.
BEGIN;
SET LOCAL lock_timeout = '3s';

-- 구버전 서버가 계속 송장을 등록할 수 있도록 NULL을 허용한다.
ALTER TABLE orders ADD COLUMN IF NOT EXISTS shipment_revision BIGINT;
UPDATE orders SET shipment_revision = 1
WHERE shipment_registered_at IS NOT NULL AND shipment_revision IS NULL;

-- 같은 트랜잭션에서 바꿔 유효하지 않은 종류가 저장될 틈을 만들지 않는다.
ALTER TABLE notifications
    DROP CONSTRAINT IF EXISTS notifications_type_check,
    ADD CONSTRAINT notifications_type_check CHECK (type IN ('CHAT_MESSAGE', 'PAYMENT_SUCCESS', 'PAYMENT_CANCELED', 'SHIPMENT_REGISTERED', 'SHIPMENT_UPDATED', 'REFUND_REQUESTED', 'REFUND_APPROVED', 'REFUND_REJECTED', 'REFUND_WITHDRAWN', 'REFUND_COMPLETED', 'PURCHASE_CONFIRMED', 'SETTLEMENT_COMPLETED', 'SETTLEMENT_FAILED'));
COMMIT;
