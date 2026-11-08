// 주문 조회 권한이 확인된 수령 정보만 응답한다.
package com.kitschcatch.backend.domain.order.dto;
import com.kitschcatch.backend.domain.order.entity.OrderShippingAddress;
public record OrderShippingAddressResponse(String recipientName,String recipientPhone,String postalCode,String addressLine1,String addressLine2,String deliveryMemo) {
 public static OrderShippingAddressResponse from(OrderShippingAddress a) { return a==null?null:new OrderShippingAddressResponse(a.getRecipientName(),a.getRecipientPhone(),a.getPostalCode(),a.getAddressLine1(),a.getAddressLine2(),a.getDeliveryMemo()); }
}
