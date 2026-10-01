// 토큰을 현재 로그인 계정으로 이전하고 이전 계정의 해제를 무시한다.
package com.kitschcatch.backend.domain.notification;

import com.kitschcatch.backend.domain.user.repository.UserRepository;
import com.kitschcatch.backend.global.exception.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DeviceTokenTransactionService {
  private final DeviceTokenRepository tokens;
  private final UserRepository users;

  public DeviceTokenTransactionService(DeviceTokenRepository tokens, UserRepository users) {
    this.tokens = tokens;
    this.users = users;
  }

  @Transactional
  public void register(long userId, DeviceTokenRequest request) {
    users.findActiveByIdForUpdate(userId)
        .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_AUTH_TOKEN));
    // 유일 키 충돌의 예외 원문에 토큰이 출력되지 않도록 충돌을 동일 값 갱신으로 처리한다.
    tokens.ensureTokenRow(userId, request.token(), request.platform());
    var token = tokens.lockByToken(request.token()).orElseThrow();
    token.register(userId, request.platform());
  }

  @Transactional
  public void remove(long userId, String value) {
    tokens
        .lockByToken(value)
        .filter(t -> t.getUserId().equals(userId) && t.isActive())
        .ifPresent(DeviceToken::deactivate);
  }
}
