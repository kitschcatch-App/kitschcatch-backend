// 회원 주소록의 수령 정보와 기본 배송지 상태를 저장한다.
package com.kitschcatch.backend.domain.address.entity;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.AccessLevel;
@Entity
@Table(name="user_shipping_addresses", indexes=@Index(name="ix_shipping_addresses_user",columnList="user_id"))
@Getter @NoArgsConstructor(access=AccessLevel.PROTECTED)
public class ShippingAddress {
 @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
 @Column(name="user_id",nullable=false) private Long userId;
 @Column(length=30) private String label;
 @Column(nullable=false,length=50) private String recipientName;
 @Column(nullable=false,length=12) private String recipientPhone;
 @Column(nullable=false,length=5) private String postalCode;
 @Column(nullable=false,length=255) private String addressLine1;
 @Column(length=255) private String addressLine2;
 @Column(length=200) private String deliveryMemo;
 @Column(name="is_default",nullable=false) private boolean isDefault;
 @Column(nullable=false) private LocalDateTime createdAt;
 @Column(nullable=false) private LocalDateTime updatedAt;
 public ShippingAddress(Long userId) { this.userId=userId; createdAt=LocalDateTime.now(); updatedAt=createdAt; }
 public void change(String label,String name,String phone,String postal,String line1,String line2,String memo) {
  this.label=label; recipientName=name; recipientPhone=phone; postalCode=postal; addressLine1=line1; addressLine2=line2; deliveryMemo=memo; updatedAt=LocalDateTime.now();
 }
 public void setDefault(boolean value) { isDefault=value; }
}
