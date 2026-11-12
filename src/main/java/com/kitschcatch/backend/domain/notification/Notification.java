// 사용자별 알림함과 중복 방지 키 및 읽음 시각을 저장한다.
package com.kitschcatch.backend.domain.notification;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(
    name = "notifications",
    uniqueConstraints =
        @UniqueConstraint(
            name = "uk_notifications_event_user",
            columnNames = {"event_key", "user_id"}),
    indexes =
        @Index(
            name = "ix_notifications_user_created",
            columnList = "user_id,created_at DESC,id DESC"))
@Getter
@NoArgsConstructor
public class Notification {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false)
  private Long userId;

  @Column(nullable = false, length = 150)
  private String eventKey;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 32)
  private NotificationType type;

  @Column(nullable = false, length = 100)
  private String title;

  @Column(nullable = false, length = 100)
  private String targetId;

  @Column(nullable = false)
  private LocalDateTime createdAt;

  private LocalDateTime readAt;

  public Notification(long userId, String eventKey, NotificationType type, String targetId) {
    this.userId = userId;
    this.eventKey = eventKey;
    this.type = type;
    this.targetId = targetId;
    this.title =
        switch (type) {
          case CHAT_MESSAGE -> "새 채팅 메시지가 도착했습니다.";
          case PAYMENT_SUCCESS -> "결제가 완료되었습니다.";
          case PAYMENT_CANCELED -> "결제가 취소되었습니다.";
          case SHIPMENT_REGISTERED -> "배송 정보가 등록되었습니다.";
          case SHIPMENT_OUT_FOR_DELIVERY -> "상품 배송이 출발했습니다.";
          case SHIPMENT_DELIVERED -> "상품 배송이 완료되었습니다.";
          case SHIPMENT_UPDATED -> "배송 정보가 변경되었습니다.";
          case REFUND_REQUESTED -> "환불 요청이 접수되었습니다.";
          case REFUND_APPROVED -> "반품이 승인되어 환불을 처리하고 있습니다.";
          case REFUND_REJECTED -> "반품 요청이 거절되었습니다.";
          case REFUND_WITHDRAWN -> "반품 요청이 철회되었습니다.";
          case REFUND_COMPLETED -> "환불 처리가 완료되었습니다.";
          case PURCHASE_CONFIRMED -> "구매가 확정되어 정산을 준비합니다.";
          case SETTLEMENT_COMPLETED -> "판매 대금 정산이 완료되었습니다.";
          case SETTLEMENT_FAILED -> "판매 대금 정산에 실패했습니다. 주문 상세에서 확인해 주세요.";
        };
    this.createdAt = LocalDateTime.now();
  }
}
