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
        };
    this.createdAt = LocalDateTime.now();
  }
}
