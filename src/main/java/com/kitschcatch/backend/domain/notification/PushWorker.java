// 커밋된 발송 작업을 잠가 소유권을 검증하고 실패를 영속 재시도한다.
package com.kitschcatch.backend.domain.notification;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PushWorker {
  private final PushProperties properties;
  private final PushDeliveryRepository deliveries;
  private final DeviceTokenRepository tokens;
  private final NotificationRepository notifications;
  private final PushAttemptRepository attempts;
  private final PushGateway gateway;

  public PushWorker(
      PushProperties properties,
      PushDeliveryRepository deliveries,
      DeviceTokenRepository tokens,
      NotificationRepository notifications,
      PushAttemptRepository attempts,
      PushGateway gateway) {
    this.properties = properties;
    this.deliveries = deliveries;
    this.tokens = tokens;
    this.notifications = notifications;
    this.attempts = attempts;
    this.gateway = gateway;
  }

  @Transactional
  public boolean processNext() {
    if (!properties.isEnabled()) return false;
    var delivery = deliveries.lockNext().orElse(null);
    if (delivery == null) return false;
    // 유한 HTTP 타임아웃 동안 토큰 행을 잠가 계정 전환이 이전 소유자의 발송과 겹치지 않게 한다.
    var token = tokens.lockById(delivery.getDeviceTokenId()).orElse(null);
    var notification = notifications.findById(delivery.getNotificationId()).orElse(null);
    if (token == null
        || notification == null
        || !token.isActive()
        || !token.getUserId().equals(notification.getUserId())
        || token.getOwnershipVersion() != delivery.getOwnershipVersion()) {
      delivery.skip();
      return true;
    }
    PushGateway.Result result;
    try {
      result = gateway.send(token, notification);
    } catch (RuntimeException exception) {
      result = PushGateway.Result.retry("GATEWAY_FAILURE");
    }
    delivery.finish(result);
    if (result.invalidToken()) token.deactivate();
    attempts.save(new PushAttempt(delivery));
    return true;
  }
}
