-- 거래 내역의 구매자·대표 이미지 스냅샷을 보완하고 본인 목록 조회 인덱스를 추가한다.
-- 기존 027·029 스키마 적용 후 애플리케이션 쓰기를 중단한 상태에서 적용한다.
BEGIN;
LOCK TABLE orders, users, post_images IN SHARE ROW EXCLUSIVE MODE;

ALTER TABLE orders ADD COLUMN IF NOT EXISTS buyer_nickname VARCHAR(50);
ALTER TABLE orders ADD COLUMN IF NOT EXISTS post_thumbnail_key VARCHAR(512);

-- 과거 닉네임·이미지는 복원할 수 없어 최초 적용 시점의 값으로만 보완한다.
-- buyer_nickname이 채워진 주문은 이미지가 null이어도 재실행 때 변경하지 않는다.
UPDATE orders o
SET buyer_nickname = u.nickname,
    post_thumbnail_key = COALESCE(o.post_thumbnail_key, (
        SELECT i.object_key FROM post_images i WHERE i.post_id = o.post_id
        ORDER BY i.sort_order, i.id LIMIT 1
    ))
FROM users u
WHERE o.user_id = u.id AND o.buyer_nickname IS NULL;

ALTER TABLE orders ALTER COLUMN buyer_nickname SET NOT NULL;

CREATE INDEX IF NOT EXISTS ix_orders_buyer_created_id ON orders(user_id, created_at DESC, id DESC);
CREATE INDEX IF NOT EXISTS ix_orders_buyer_status_created_id ON orders(user_id, order_status, created_at DESC, id DESC);
CREATE INDEX IF NOT EXISTS ix_orders_seller_created_id ON orders(seller_id, created_at DESC, id DESC);
CREATE INDEX IF NOT EXISTS ix_orders_seller_status_created_id ON orders(seller_id, order_status, created_at DESC, id DESC);
COMMIT;
