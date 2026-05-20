package com.kitschcatch.backend.domain.auth.repository;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.LockModeType;
import java.lang.reflect.Method;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Lock;

class AuthRepositoryLockTest {

	@Test
	@DisplayName("리프레시 토큰 소비 조회는 쓰기 락을 사용한다")
	void refreshTokenLookupUsesPessimisticWriteLock() throws Exception {
		Method method = RefreshTokenRepository.class.getMethod("findByTokenHashForUpdate", String.class);

		assertThat(method.getAnnotation(Lock.class).value()).isEqualTo(LockModeType.PESSIMISTIC_WRITE);
	}

	@Test
	@DisplayName("로그인 nonce 소비 조회는 쓰기 락을 사용한다")
	void loginNonceLookupUsesPessimisticWriteLock() throws Exception {
		Method method = LoginNonceRepository.class.getMethod("findByNonceHashForUpdate", String.class);

		assertThat(method.getAnnotation(Lock.class).value()).isEqualTo(LockModeType.PESSIMISTIC_WRITE);
	}
}
