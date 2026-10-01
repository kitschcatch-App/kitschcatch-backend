// 기기 등록과 해제를 독립 트랜잭션 서비스로 전달한다.
package com.kitschcatch.backend.domain.notification;

import org.springframework.stereotype.Service;

@Service
public class DeviceTokenService {
  private final DeviceTokenTransactionService transactions;

  public DeviceTokenService(DeviceTokenTransactionService transactions) {
    this.transactions = transactions;
  }

  public void register(long userId, DeviceTokenRequest request) {
    transactions.register(userId, request);
  }

  public void remove(long userId, String token) {
    transactions.remove(userId, token);
  }
}
