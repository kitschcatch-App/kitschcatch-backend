-- 검증된 지급 수취인과 주문별 고정 지급 대상을 보존한다.
BEGIN;
LOCK TABLE orders IN SHARE ROW EXCLUSIVE MODE;
ALTER TABLE orders ADD COLUMN IF NOT EXISTS settlement_destination VARCHAR(35);
CREATE TABLE IF NOT EXISTS settlement_recipients (
    seller_id BIGINT PRIMARY KEY REFERENCES users(id),
    provider_seller_id VARCHAR(35) NOT NULL UNIQUE,
    registered_by BIGINT NOT NULL,
    verified_at TIMESTAMP NOT NULL
);
COMMIT;
