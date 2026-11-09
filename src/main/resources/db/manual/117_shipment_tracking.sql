-- 운송장 버전별 배송 상태와 웹훅 작업을 보관한다.
BEGIN;
SET LOCAL lock_timeout = '3s';
CREATE TABLE IF NOT EXISTS shipment_tracking (
    order_id bigint NOT NULL REFERENCES orders(id) ON DELETE CASCADE,
    revision bigint NOT NULL CHECK (revision > 0),
    carrier_id varchar(40) NOT NULL,
    tracking_number varchar(40) NOT NULL,
    callback_token uuid NOT NULL UNIQUE DEFAULT gen_random_uuid(),
    created_at timestamptz NOT NULL DEFAULT now(),
    expires_at timestamptz,
    status varchar(30) NOT NULL DEFAULT 'UNKNOWN',
    event_at timestamptz,
    checked_at timestamptz,
    failed boolean NOT NULL DEFAULT false,
    pending boolean NOT NULL DEFAULT true,
    next_check_at timestamptz,
    attempts integer NOT NULL DEFAULT 0,
    lease_token uuid,
    lease_until timestamptz,
    PRIMARY KEY(order_id, revision)
);
CREATE TABLE IF NOT EXISTS shipment_tracking_events (
    order_id bigint NOT NULL,
    revision bigint NOT NULL,
    occurred_at timestamptz NOT NULL,
    status varchar(30) NOT NULL,
    PRIMARY KEY(order_id, revision, occurred_at, status),
    FOREIGN KEY(order_id, revision) REFERENCES shipment_tracking(order_id, revision) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS ix_shipment_tracking_due ON shipment_tracking(next_check_at)
    WHERE next_check_at IS NOT NULL;
INSERT INTO shipment_tracking(order_id, revision, carrier_id, tracking_number, next_check_at)
SELECT id, coalesce(shipment_revision,1),
    CASE shipment_carrier_code WHEN 'CJ_LOGISTICS' THEN 'kr.cjlogistics' ELSE 'kr.hanjin' END,
    shipment_tracking_number, now()
FROM orders WHERE order_status IN ('PAID','PURCHASE_CONFIRMED')
  AND shipment_registered_at >= now() - interval '30 days'
  AND shipment_tracking_number IS NOT NULL AND shipment_carrier_code IN ('CJ_LOGISTICS','HANJIN')
ON CONFLICT DO NOTHING;
ALTER TABLE notifications
    DROP CONSTRAINT IF EXISTS notifications_type_check,
    ADD CONSTRAINT notifications_type_check CHECK (type IN ('CHAT_MESSAGE', 'PAYMENT_SUCCESS', 'PAYMENT_CANCELED', 'SHIPMENT_REGISTERED', 'SHIPMENT_UPDATED', 'REFUND_REQUESTED', 'REFUND_APPROVED', 'REFUND_REJECTED', 'REFUND_WITHDRAWN', 'REFUND_COMPLETED', 'PURCHASE_CONFIRMED', 'SETTLEMENT_COMPLETED', 'SETTLEMENT_FAILED', 'SHIPMENT_OUT_FOR_DELIVERY', 'SHIPMENT_DELIVERED'));
COMMIT;
