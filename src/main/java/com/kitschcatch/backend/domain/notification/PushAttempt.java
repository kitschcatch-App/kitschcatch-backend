// 각 푸시 시도의 안전한 결과 코드와 시각을 기록한다.
package com.kitschcatch.backend.domain.notification;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "push_attempts")
@NoArgsConstructor
public class PushAttempt {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false)
  private Long deliveryId;

  @Column(nullable = false)
  private int attemptNumber;

  @Column(nullable = false, length = 100)
  private String resultCode;

  @Column(nullable = false)
  private LocalDateTime attemptedAt;

  public PushAttempt(PushDelivery delivery) {
    deliveryId = delivery.getId();
    attemptNumber = delivery.getAttempts();
    resultCode = delivery.getLastCode();
    attemptedAt = LocalDateTime.now();
  }
}
