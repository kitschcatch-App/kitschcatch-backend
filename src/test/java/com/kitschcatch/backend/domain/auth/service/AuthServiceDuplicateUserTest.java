package com.kitschcatch.backend.domain.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.kitschcatch.backend.domain.auth.dto.AuthTokenResponse;
import com.kitschcatch.backend.domain.auth.oidc.KakaoOidcTokenVerifier;
import com.kitschcatch.backend.domain.auth.oidc.KakaoOidcUser;
import com.kitschcatch.backend.domain.auth.repository.LoginNonceRepository;
import com.kitschcatch.backend.domain.auth.repository.RefreshTokenRepository;
import com.kitschcatch.backend.domain.user.entity.AuthProvider;
import com.kitschcatch.backend.domain.user.entity.User;
import com.kitschcatch.backend.global.security.JwtProperties;
import com.kitschcatch.backend.global.security.JwtTokenProvider;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

class AuthServiceDuplicateUserTest {

	@Test
	@DisplayName("최초 로그인 중 중복 사용자 생성이 발생하면 저장된 사용자를 재조회한다")
	void duplicateUserCreationRetriesByReadingSavedUser() {
		KakaoOidcTokenVerifier verifier = (idToken, nonce) -> new KakaoOidcUser(
			"123456789",
			"kakao@example.com",
			"kakao-nickname"
		);
		RefreshTokenRepository refreshTokenRepository = mock(RefreshTokenRepository.class);
		LoginNonceRepository loginNonceRepository = mock(LoginNonceRepository.class);
		JwtTokenProvider jwtTokenProvider = new JwtTokenProvider(new JwtProperties(
			"test-jwt-secret-must-be-at-least-32-bytes",
			Duration.ofMinutes(30),
			Duration.ofDays(14)
		));
		KakaoUserService kakaoUserService = mock(KakaoUserService.class);
		User savedByConcurrentRequest = User.builder()
			.nickname("kakao-nickname")
			.email("kakao@example.com")
			.authProvider(AuthProvider.KAKAO)
			.providerUserId("123456789")
			.build();
		ReflectionTestUtils.setField(savedByConcurrentRequest, "id", 1L);

		when(loginNonceRepository.consumeByRawNonce("nonce-value")).thenReturn(true);
		when(kakaoUserService.findKakaoUser("123456789"))
			.thenReturn(Optional.empty())
			.thenReturn(Optional.of(savedByConcurrentRequest));
		when(kakaoUserService.createKakaoUser(any(KakaoOidcUser.class)))
			.thenThrow(new DataIntegrityViolationException("duplicate"));

		AuthService authService = new AuthService(
			verifier,
			kakaoUserService,
			refreshTokenRepository,
			loginNonceRepository,
			jwtTokenProvider
		);

		AuthTokenResponse response = authService.loginWithKakaoIdToken("kakao-sdk-id-token", "nonce-value");

		assertThat(response.user().id()).isEqualTo(1L);
		verify(kakaoUserService, times(2)).findKakaoUser("123456789");
	}
}
