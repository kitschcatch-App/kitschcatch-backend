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
    if (!users.existsById(userId)) throw new BusinessException(ErrorCode.USER_NOT_FOUND);
    var token = tokens.lockByToken(request.token()).orElse(null);
    if (token == null)
      tokens.saveAndFlush(new DeviceToken(userId, request.token(), request.platform()));
    else token.register(userId, request.platform());
  }

  @Transactional
  public void remove(long userId, String value) {
    tokens
        .lockByToken(value)
        .filter(t -> t.getUserId().equals(userId) && t.isActive())
        .ifPresent(DeviceToken::deactivate);
  }
}
