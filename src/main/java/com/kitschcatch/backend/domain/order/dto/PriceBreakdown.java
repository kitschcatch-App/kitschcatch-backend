// 구매자에게 청구할 상품금액·배송비·수수료·총액을 반환한다.
package com.kitschcatch.backend.domain.order.dto;
import com.kitschcatch.backend.domain.order.entity.OrderPricing;
public record PriceBreakdown(String currency,String pricingPolicyVersion,long productAmount,long shippingAmount,long buyerFeeAmount,long totalAmount,long sellerFeeAmount,long sellerPayoutAmount) {
 public static PriceBreakdown from(OrderPricing p) {return p==null?null:new PriceBreakdown("KRW",p.getPolicy(),p.getProductAmount(),p.getShippingAmount(),p.getBuyerFeeAmount(),p.total(),p.getSellerFeeAmount(),p.getSellerPayoutAmount());}
}
