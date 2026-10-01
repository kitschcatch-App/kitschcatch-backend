// 신규 토큰의 동시 등록 충돌을 롤백 후 재시도한다.
package com.kitschcatch.backend.domain.notification;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

@Service
public class DeviceTokenService {
  private final DeviceTokenTransactionService transactions;

  public DeviceTokenService(DeviceTokenTransactionService transactions) {
    this.transactions = transactions;
  }

  public void register(long userId, DeviceTokenRequest request) {
    // 처음 등록하는 토큰의 유일 키 경쟁만 새 트랜잭션에서 재조회한다.
    for (int attempt = 0; ; attempt++) {
      try {
        transactions.register(userId, request);
        return;
      } catch (DataIntegrityViolationException exception) {
        if (attempt >= 2) throw exception;
      }
    }
  }

  public void remove(long userId, String token) {
    transactions.remove(userId, token);
  }
}
