// 주문 시점의 수령 정보를 주소록과 독립적으로 보존한다.
package com.kitschcatch.backend.domain.order.entity;
import com.kitschcatch.backend.domain.address.entity.ShippingAddress;
import jakarta.persistence.*;
import lombok.*;
@Embeddable @Getter @NoArgsConstructor(access=AccessLevel.PROTECTED)
public class OrderShippingAddress {
 @Column(name="shipping_recipient_name",length=50,updatable=false) private String recipientName;
 @Column(name="shipping_recipient_phone",length=12,updatable=false) private String recipientPhone;
 @Column(name="shipping_postal_code",length=5,updatable=false) private String postalCode;
 @Column(name="shipping_address_line1",length=255,updatable=false) private String addressLine1;
 @Column(name="shipping_address_line2",length=255,updatable=false) private String addressLine2;
 @Column(name="shipping_delivery_memo",length=200,updatable=false) private String deliveryMemo;
 public OrderShippingAddress(ShippingAddress a) { recipientName=a.getRecipientName(); recipientPhone=a.getRecipientPhone(); postalCode=a.getPostalCode(); addressLine1=a.getAddressLine1(); addressLine2=a.getAddressLine2(); deliveryMemo=a.getDeliveryMemo(); }
}
