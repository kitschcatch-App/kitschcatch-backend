-- 상품 운임과 5분 견적 및 주문 금액을 추가하며 기존 청구액은 보존한다.
BEGIN;
ALTER TABLE posts ADD COLUMN IF NOT EXISTS shipping_amount BIGINT;
ALTER TABLE orders ADD COLUMN IF NOT EXISTS pricing_policy VARCHAR(40);
ALTER TABLE orders ADD COLUMN IF NOT EXISTS product_amount BIGINT;
ALTER TABLE orders ADD COLUMN IF NOT EXISTS shipping_amount BIGINT;
ALTER TABLE orders ADD COLUMN IF NOT EXISTS buyer_fee_amount BIGINT;
ALTER TABLE orders ADD COLUMN IF NOT EXISTS seller_fee_amount BIGINT;
ALTER TABLE orders ADD COLUMN IF NOT EXISTS seller_payout_amount BIGINT;
ALTER TABLE orders ADD COLUMN IF NOT EXISTS quote_id VARCHAR(50);
CREATE UNIQUE INDEX IF NOT EXISTS uk_orders_quote ON orders(quote_id) WHERE quote_id IS NOT NULL;
CREATE TABLE IF NOT EXISTS order_quotes (
 id VARCHAR(50) PRIMARY KEY,
 user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
 post_id BIGINT NOT NULL REFERENCES posts(id),
 address_id BIGINT NOT NULL,
 address_updated_at TIMESTAMP NOT NULL,
 expires_at TIMESTAMP NOT NULL,
 used_order_number VARCHAR(50),
 pricing_policy VARCHAR(40) NOT NULL,
 product_amount BIGINT NOT NULL,
 shipping_amount BIGINT NOT NULL,
 buyer_fee_amount BIGINT NOT NULL,
 seller_fee_amount BIGINT NOT NULL,
 seller_payout_amount BIGINT NOT NULL
);
DO $$ BEGIN
 IF NOT EXISTS(SELECT 1 FROM pg_constraint WHERE conname='ck_post_shipping' AND conrelid='posts'::regclass) THEN
 ALTER TABLE posts ADD CONSTRAINT ck_post_shipping CHECK(shipping_amount IS NULL OR (shipping_amount>=0 AND shipping_amount<1000000000));
 END IF;
 IF NOT EXISTS(SELECT 1 FROM pg_constraint WHERE conname='ck_order_pricing_v2' AND conrelid='orders'::regclass) THEN
 ALTER TABLE orders ADD CONSTRAINT ck_order_pricing_v2 CHECK(
 (pricing_policy IS NULL AND quote_id IS NULL) OR (
 pricing_policy IS NOT NULL AND pricing_policy='V2_SELLER_500_BUYER_0' AND quote_id IS NOT NULL
 AND product_amount IS NOT NULL AND shipping_amount IS NOT NULL AND buyer_fee_amount IS NOT NULL
 AND seller_fee_amount IS NOT NULL AND seller_payout_amount IS NOT NULL
 AND product_amount>0 AND shipping_amount>=0 AND buyer_fee_amount=0
 AND amount=product_amount+shipping_amount AND amount<1000000000
 AND seller_fee_amount=floor(product_amount::numeric*500/10000)
 AND seller_payout_amount=amount-seller_fee_amount AND seller_payout_amount>0));
 END IF;
 IF NOT EXISTS(SELECT 1 FROM pg_constraint WHERE conname='ck_order_pricing_presence' AND conrelid='orders'::regclass) THEN
 ALTER TABLE orders ADD CONSTRAINT ck_order_pricing_presence CHECK(
 pricing_policy IS NOT NULL OR (product_amount IS NULL AND shipping_amount IS NULL
 AND buyer_fee_amount IS NULL AND seller_fee_amount IS NULL AND seller_payout_amount IS NULL));
 END IF;
 IF NOT EXISTS(SELECT 1 FROM pg_constraint WHERE conname='ck_quote_pricing' AND conrelid='order_quotes'::regclass) THEN
 ALTER TABLE order_quotes ADD CONSTRAINT ck_quote_pricing CHECK(
 pricing_policy='V2_SELLER_500_BUYER_0' AND product_amount>0 AND shipping_amount>=0 AND buyer_fee_amount=0
 AND product_amount+shipping_amount<1000000000 AND seller_fee_amount=floor(product_amount::numeric*500/10000)
 AND seller_payout_amount=product_amount+shipping_amount-seller_fee_amount AND seller_payout_amount>0);
 END IF;
END $$;
COMMIT;
