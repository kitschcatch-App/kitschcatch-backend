// 사용자와 배송지에 귀속된 5분 견적 및 재요청에 사용할 주문 번호를 저장한다.
package com.kitschcatch.backend.domain.order.entity;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.*;
@Entity @Table(name="order_quotes") @Getter @NoArgsConstructor(access=AccessLevel.PROTECTED)
public class OrderQuote {
 @Id @Column(length=50) private String id;
 @Column(nullable=false) private Long userId;
 @Column(nullable=false) private Long postId;
 @Column(nullable=false) private Long addressId;
 @Column(nullable=false) private LocalDateTime addressUpdatedAt;
 @Column(nullable=false) private LocalDateTime expiresAt;
 @Column(length=50) private String usedOrderNumber;
 @Embedded private OrderPricing pricing;
 public OrderQuote(Long userId,Long postId,Long addressId,LocalDateTime addressUpdatedAt,OrderPricing pricing) {
  this.id="QUOTE-"+UUID.randomUUID();this.userId=userId;this.postId=postId;this.addressId=addressId;this.addressUpdatedAt=addressUpdatedAt;this.pricing=pricing;this.expiresAt=LocalDateTime.now().plusMinutes(5);
 }
 public void consume(String orderNumber) {if(usedOrderNumber!=null)throw new IllegalStateException("이미 사용한 견적");usedOrderNumber=orderNumber;}
}
