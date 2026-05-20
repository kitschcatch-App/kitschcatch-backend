package com.kitschcatch.backend.global.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.kitschcatch.backend.global.exception.BusinessException;
import com.kitschcatch.backend.global.exception.ErrorCode;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class JwtTokenProviderTest {

	private final JwtTokenProvider jwtTokenProvider = new JwtTokenProvider(
		new JwtProperties(
			"test-jwt-secret-must-be-at-least-32-bytes",
			Duration.ofMinutes(30),
			Duration.ofDays(14)
		)
	);

	@Test
	@DisplayName("사용자 ID로 access token을 발급하고 검증한다")
	void issueAndParseAccessToken() {
		String token = jwtTokenProvider.createAccessToken(1L);

		AuthenticatedUser authenticatedUser = jwtTokenProvider.parseAccessToken(token);

		assertThat(authenticatedUser.userId()).isEqualTo(1L);
	}

	@Test
	@DisplayName("refresh token은 access token 검증에 사용할 수 없다")
	void refreshTokenCannotBeUsedAsAccessToken() {
		String token = jwtTokenProvider.createRefreshToken(1L);

		assertThatThrownBy(() -> jwtTokenProvider.parseAccessToken(token))
			.isInstanceOf(BusinessException.class)
			.extracting("errorCode")
			.isEqualTo(ErrorCode.INVALID_AUTH_TOKEN);
	}

	@Test
	@DisplayName("refresh token 값은 저장용 SHA-256 해시로 변환한다")
	void hashRefreshToken() {
		String token = jwtTokenProvider.createRefreshToken(1L);

		String hash = jwtTokenProvider.hashToken(token);

		assertThat(hash).hasSize(64);
		assertThat(hash).doesNotContain(token);
	}
}
