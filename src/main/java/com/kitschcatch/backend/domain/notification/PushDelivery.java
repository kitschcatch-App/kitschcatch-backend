// 기기별 영속 발송 작업과 재시도 결과를 저장한다.
package com.kitschcatch.backend.domain.notification;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(
    name = "push_deliveries",
    uniqueConstraints =
        @UniqueConstraint(
            name = "uk_push_delivery_notification_device",
            columnNames = {"notification_id", "device_token_id"}),
    indexes = @Index(name = "ix_push_delivery_due", columnList = "status,next_attempt_at,id"))
@Getter
@NoArgsConstructor
public class PushDelivery {
  public enum Status {
    PENDING,
    SENT,
    FAILED,
    SKIPPED
  }

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false)
  private Long notificationId;

  @Column(nullable = false)
  private Long deviceTokenId;

  @Column(nullable = false)
  private long ownershipVersion;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 16)
  private Status status = Status.PENDING;

  @Column(nullable = false)
  private int attempts;

  @Column(nullable = false)
  private LocalDateTime nextAttemptAt = LocalDateTime.now();

  @Column(length = 100)
  private String lastCode;

  @Column(length = 512)
  private String providerMessageId;

  public PushDelivery(long notificationId, DeviceToken token) {
    this.notificationId = notificationId;
    deviceTokenId = token.getId();
    ownershipVersion = token.getOwnershipVersion();
  }

  public void skip() {
    status = Status.SKIPPED;
    lastCode = "OWNERSHIP_CHANGED";
  }

  public void finish(PushGateway.Result result) {
    attempts++;
    lastCode = result.code();
    providerMessageId = result.messageId();
    if (result.success()) status = Status.SENT;
    else if (!result.retryable() || attempts >= 5) status = Status.FAILED;
    else
      nextAttemptAt =
          LocalDateTime.now()
              .plusSeconds(
                  Math.max(result.retryAfterSeconds(), Math.min(3600, 60L << (attempts - 1))));
  }
}
