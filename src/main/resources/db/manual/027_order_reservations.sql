-- 기존 주문 데이터에 예약 소유권과 주문 스냅샷 및 단일 결제 제약을 추가한다.
-- 애플리케이션 쓰기를 중단하고 PG 결과와 기존 활성 주문을 확인한 뒤 한 번 적용한다.
BEGIN;
LOCK TABLE posts, orders, payments IN SHARE ROW EXCLUSIVE MODE;

DO $$
BEGIN
    IF EXISTS (SELECT order_id FROM payments GROUP BY order_id HAVING count(*) > 1) THEN
        RAISE EXCEPTION '주문당 중복 결제가 있습니다. PG 결과를 확인하고 정리한 뒤 적용하세요.';
    END IF;
    IF EXISTS (
        SELECT post_id FROM orders WHERE order_status IN ('PENDING', 'PAID')
        GROUP BY post_id HAVING count(*) > 1
    ) THEN
        RAISE EXCEPTION '상품당 활성 주문이 여러 건입니다. PG 결과를 확인하고 정리한 뒤 적용하세요.';
    END IF;
    IF EXISTS (
        SELECT 1 FROM orders o JOIN posts p ON p.id = o.post_id
        WHERE o.order_status IN ('PENDING', 'PAID') AND p.deleted_at IS NOT NULL
    ) THEN
        RAISE EXCEPTION '삭제된 상품에 활성 주문이 있습니다. 거래 상태를 확인한 뒤 적용하세요.';
    END IF;
END $$;

ALTER TABLE posts ADD COLUMN active_order_number varchar(50);
ALTER TABLE orders ADD COLUMN snapshot_post_id bigint;
ALTER TABLE orders ADD COLUMN post_title varchar(100);
ALTER TABLE orders ADD COLUMN seller_id bigint;
ALTER TABLE orders ADD COLUMN seller_nickname varchar(50);
ALTER TABLE orders ADD COLUMN reservation_expires_at timestamp;

-- 과거 상품명과 닉네임은 복원할 수 없으므로 현재 값을 보완한다. 거래 금액은 기존 amount를 유지한다.
UPDATE orders o SET snapshot_post_id = p.id, post_title = p.title,
    seller_id = u.id, seller_nickname = u.nickname
FROM posts p JOIN users u ON u.id = p.user_id
WHERE o.post_id = p.id;

ALTER TABLE orders ALTER COLUMN snapshot_post_id SET NOT NULL;
ALTER TABLE orders ALTER COLUMN post_title SET NOT NULL;
ALTER TABLE orders ALTER COLUMN seller_id SET NOT NULL;
ALTER TABLE orders ALTER COLUMN seller_nickname SET NOT NULL;
ALTER TABLE payments ADD CONSTRAINT uk_payments_order_id UNIQUE (order_id);
CREATE INDEX idx_orders_reservation_expiry ON orders (order_status, reservation_expires_at);

UPDATE posts p SET active_order_number = o.order_number,
    product_status = CASE WHEN o.order_status = 'PAID' THEN 'SOLD_OUT' ELSE 'RESERVED' END
FROM orders o WHERE o.post_id = p.id AND o.order_status IN ('PENDING', 'PAID');

-- 기존 주문의 만료 시각은 NULL로 유지하여 확인되지 않은 결제를 자동 해제하지 않는다.
COMMIT;
