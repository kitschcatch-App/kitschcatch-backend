// H2 방언에서도 충돌 무시 삽입과 계정 이전 계약이 유지되는지 검증한다.
package com.kitschcatch.backend.domain.notification;

import static org.assertj.core.api.Assertions.assertThat;

import com.kitschcatch.backend.domain.user.entity.AuthProvider;
import com.kitschcatch.backend.domain.user.entity.User;
import com.kitschcatch.backend.domain.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;

@DataJpaTest(showSql = false)
@Import(DeviceTokenTransactionService.class)
class DeviceTokenH2Test {
  @Autowired DeviceTokenRepository tokens;
  @Autowired UserRepository users;
  @Autowired DeviceTokenTransactionService service;

  @Test
  void insertConflictPreservesOneTokenAndOwnershipGeneration() {
    var first =
        users.saveAndFlush(
            User.builder()
                .nickname("처음")
                .email("first@test.invalid")
                .authProvider(AuthProvider.KAKAO)
                .providerUserId("first")
                .build());
    var second =
        users.saveAndFlush(
            User.builder()
                .nickname("다음")
                .email("second@test.invalid")
                .authProvider(AuthProvider.KAKAO)
                .providerUserId("second")
                .build());
    var request = new DeviceTokenRequest("h2-synthetic-token", "IOS");
    service.register(first.getId(), request);
    service.register(first.getId(), request);
    service.register(second.getId(), request);
    service.remove(first.getId(), request.token());
    var token = tokens.lockByToken(request.token()).orElseThrow();
    assertThat(tokens.count()).isEqualTo(1);
    assertThat(token.getUserId()).isEqualTo(second.getId());
    assertThat(token.getOwnershipVersion()).isEqualTo(1);
    assertThat(token.isActive()).isTrue();
  }
}
