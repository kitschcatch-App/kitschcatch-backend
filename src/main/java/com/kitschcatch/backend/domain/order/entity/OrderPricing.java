// 주문 당시 상품·배송·수수료와 지급액을 원 단위 정수로 고정한다.
package com.kitschcatch.backend.domain.order.entity;
import jakarta.persistence.*;
import lombok.*;
@Embeddable @Getter @NoArgsConstructor(access=AccessLevel.PROTECTED)
public class OrderPricing {
 public static final String POLICY="V2_SELLER_500_BUYER_0";
 @Column(name="pricing_policy",length=40,updatable=false) private String policy;
 @Column(name="product_amount",updatable=false) private Long productAmount;
 @Column(name="shipping_amount",updatable=false) private Long shippingAmount;
 @Column(name="buyer_fee_amount",updatable=false) private Long buyerFeeAmount;
 @Column(name="seller_fee_amount",updatable=false) private Long sellerFeeAmount;
 @Column(name="seller_payout_amount",updatable=false) private Long sellerPayoutAmount;
 public static OrderPricing calculate(long product,long shipping) {
  if(product<=0 || shipping<0) throw new IllegalArgumentException("금액 범위 오류");
  var p=new OrderPricing();p.policy=POLICY;p.productAmount=product;p.shippingAmount=shipping;p.buyerFeeAmount=0L;
  p.sellerFeeAmount=java.math.BigInteger.valueOf(product).multiply(java.math.BigInteger.valueOf(500)).divide(java.math.BigInteger.valueOf(10000)).longValueExact();
  p.sellerPayoutAmount=Math.subtractExact(p.total(),p.sellerFeeAmount);
  if(p.total()>=1_000_000_000L || p.sellerPayoutAmount<=0) throw new IllegalArgumentException("지원 금액 초과");
  return p;
 }
 public long total() {return Math.addExact(Math.addExact(productAmount,shippingAmount),buyerFeeAmount);}
}
