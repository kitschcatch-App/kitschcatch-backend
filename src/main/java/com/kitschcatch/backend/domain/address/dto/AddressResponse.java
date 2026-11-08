// 저장된 배송지의 정규화 값과 기본 여부를 반환한다.
package com.kitschcatch.backend.domain.address.dto;
import com.kitschcatch.backend.domain.address.entity.ShippingAddress;
import java.time.LocalDateTime;
public record AddressResponse(Long id,String label,String recipientName,String recipientPhone,String postalCode,
 String addressLine1,String addressLine2,String deliveryMemo,boolean isDefault,LocalDateTime createdAt,LocalDateTime updatedAt) {
 public static AddressResponse from(ShippingAddress a) { return new AddressResponse(a.getId(),a.getLabel(),a.getRecipientName(),a.getRecipientPhone(),a.getPostalCode(),a.getAddressLine1(),a.getAddressLine2(),a.getDeliveryMemo(),a.isDefault(),a.getCreatedAt(),a.getUpdatedAt()); }
}
