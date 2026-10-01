// 푸시 제공자의 안전한 결과와 재시도 분류 계약을 정의한다.
package com.kitschcatch.backend.domain.notification;

public interface PushGateway {
  Result send(DeviceToken token, Notification notification);

  record Result(
      boolean success,
      boolean retryable,
      boolean invalidToken,
      String code,
      String messageId,
      long retryAfterSeconds) {
    public static Result retry(String code) {
      return new Result(false, true, false, code, null, 60);
    }
  }
}
