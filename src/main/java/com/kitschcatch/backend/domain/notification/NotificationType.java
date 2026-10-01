// 현재 연결된 채팅과 결제 알림 종류를 정의한다.
package com.kitschcatch.backend.domain.notification;

public enum NotificationType {
  CHAT_MESSAGE,
  PAYMENT_SUCCESS,
  PAYMENT_CANCELED
}
