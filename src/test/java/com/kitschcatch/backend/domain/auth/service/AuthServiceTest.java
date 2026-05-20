package com.kitschcatch.backend.domain.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.kitschcatch.backend.domain.auth.dto.AuthTokenResponse;
import com.kitschcatch.backend.domain.auth.dto.KakaoNonceResponse;
import com.kitschcatch.backend.domain.auth.oidc.KakaoOidcUser;
import com.kitschcatch.backend.domain.auth.oidc.KakaoOidcTokenVerifier;
import com.kitschcatch.backend.domain.auth.repository.LoginNonceRepository;
import com.kitschcatch.backend.domain.auth.repository.RefreshTokenRepository;
import com.kitschcatch.backend.domain.user.entity.AuthProvider;
import com.kitschcatch.backend.domain.user.entity.User;
import com.kitschcatch.backend.domain.user.repository.UserRepository;
import com.kitschcatch.backend.global.exception.BusinessException;
import com.kitschcatch.backend.global.exception.ErrorCode;
import com.kitschcatch.backend.global.security.JwtTokenProvider;
import com.kitschcatch.backend.global.security.JwtProperties;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

@DataJpaTest(properties = {
	"spring.datasource.url=jdbc:h2:mem:auth-service;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE",
	"spring.datasource.driver-class-name=org.h2.Driver",
	"spring.datasource.username=sa",
	"spring.datasource.password=",
	"spring.jpa.hibernate.ddl-auto=create-drop"
})
@Import({
	AuthService.class,
	AuthServiceTest.TestConfig.class
})
class AuthServiceTest {

	@Autowired
	private AuthService authService;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private RefreshTokenRepository refreshTokenRepository;

	@Autowired
	private LoginNonceRepository loginNonceRepository;

	@Autowired
	private FakeKakaoOidcTokenVerifier kakaoOidcTokenVerifier;

	@BeforeEach
	void setUp() {
		kakaoOidcTokenVerifier.user = new KakaoOidcUser(
			"123456789",
			"kakao@example.com",
			"kakao-nickname"
		);
	}

	@Test
	@DisplayName("카카오 SDK ID 토큰으로 신규 사용자를 생성하고 자체 토큰 쌍을 발급한다")
	void loginWithKakaoIdTokenCreatesUserAndIssuesTokens() {
		KakaoNonceResponse nonceResponse = authService.createKakaoLoginNonce();

		AuthTokenResponse response = authService.loginWithKakaoIdToken("kakao-sdk-id-token", nonceResponse.nonce());

		Optional<User> savedUser = userRepository.findByAuthProviderAndProviderUserId(
			AuthProvider.KAKAO,
			"123456789"
		);

		assertThat(savedUser).isPresent();
		assertThat(response.accessToken()).isNotBlank();
		assertThat(response.refreshToken()).isNotBlank();
		assertThat(response.user().id()).isEqualTo(savedUser.get().getId());
		assertThat(refreshTokenRepository.findAll()).hasSize(1);
		assertThat(loginNonceRepository.findAll())
			.singleElement()
			.satisfies(nonce -> assertThat(nonce.isConsumed()).isTrue());
		assertThat(kakaoOidcTokenVerifier.lastIdToken).isEqualTo("kakao-sdk-id-token");
		assertThat(kakaoOidcTokenVerifier.lastNonce).isEqualTo(nonceResponse.nonce());
	}

	@Test
	@DisplayName("카카오 SDK ID 토큰 로그인은 기존 카카오 사용자를 재사용한다")
	void loginWithKakaoIdTokenReusesExistingUser() {
		User existingUser = User.builder()
			.nickname("old-nickname")
			.email("old@example.com")
			.authProvider(AuthProvider.KAKAO)
			.providerUserId("123456789")
			.build();
		userRepository.save(existingUser);
		KakaoNonceResponse nonceResponse = authService.createKakaoLoginNonce();

		AuthTokenResponse response = authService.loginWithKakaoIdToken("kakao-sdk-id-token", nonceResponse.nonce());

		assertThat(userRepository.findAll()).hasSize(1);
		assertThat(response.user().id()).isEqualTo(existingUser.getId());
	}

	@Test
	@DisplayName("카카오 SDK ID 토큰에 이메일이 없으면 가입과 로그인을 거부한다")
	void loginWithKakaoIdTokenWithoutEmailFails() {
		kakaoOidcTokenVerifier.user = new KakaoOidcUser("123456789", null, "kakao-nickname");
		KakaoNonceResponse nonceResponse = authService.createKakaoLoginNonce();

		assertThatThrownBy(() -> authService.loginWithKakaoIdToken("kakao-sdk-id-token", nonceResponse.nonce()))
			.isInstanceOf(BusinessException.class)
			.extracting("errorCode")
			.isEqualTo(ErrorCode.KAKAO_EMAIL_REQUIRED);
	}

	@Test
	@DisplayName("카카오 로그인 nonce는 한 번만 사용할 수 있다")
	void loginNonceCanBeConsumedOnlyOnce() {
		KakaoNonceResponse nonceResponse = authService.createKakaoLoginNonce();

		authService.loginWithKakaoIdToken("kakao-sdk-id-token", nonceResponse.nonce());

		assertThatThrownBy(() -> authService.loginWithKakaoIdToken("kakao-sdk-id-token", nonceResponse.nonce()))
			.isInstanceOf(BusinessException.class)
			.extracting("errorCode")
			.isEqualTo(ErrorCode.KAKAO_LOGIN_FAILED);
	}

	@Test
	@DisplayName("저장되지 않은 nonce로는 카카오 로그인을 거부한다")
	void loginWithUnknownNonceFails() {
		assertThatThrownBy(() -> authService.loginWithKakaoIdToken("kakao-sdk-id-token", "unknown-nonce"))
			.isInstanceOf(BusinessException.class)
			.extracting("errorCode")
			.isEqualTo(ErrorCode.KAKAO_LOGIN_FAILED);
	}

	@Test
	@DisplayName("리프레시 토큰은 한 번 사용하면 폐기되고 새 토큰으로 회전한다")
	void refreshTokenRotatesStoredToken() {
		KakaoNonceResponse nonceResponse = authService.createKakaoLoginNonce();
		AuthTokenResponse loginResponse = authService.loginWithKakaoIdToken("kakao-sdk-id-token", nonceResponse.nonce());

		AuthTokenResponse refreshResponse = authService.refresh(loginResponse.refreshToken());

		assertThat(refreshResponse.refreshToken()).isNotEqualTo(loginResponse.refreshToken());
		assertThat(refreshTokenRepository.findAll()).hasSize(2);
		assertThat(refreshTokenRepository.findAll())
			.filteredOn(token -> token.isRevoked())
			.hasSize(1);
		assertThatThrownBy(() -> authService.refresh(loginResponse.refreshToken()))
			.isInstanceOf(BusinessException.class)
			.extracting("errorCode")
			.isEqualTo(ErrorCode.INVALID_REFRESH_TOKEN);
	}

	@Test
	@DisplayName("로그아웃은 저장된 리프레시 토큰을 폐기한다")
	void logoutRevokesRefreshToken() {
		KakaoNonceResponse nonceResponse = authService.createKakaoLoginNonce();
		AuthTokenResponse loginResponse = authService.loginWithKakaoIdToken("kakao-sdk-id-token", nonceResponse.nonce());

		authService.logout(loginResponse.refreshToken());

		assertThat(refreshTokenRepository.findAll())
			.singleElement()
			.satisfies(token -> assertThat(token.isRevoked()).isTrue());
	}

	@TestConfiguration
	static class TestConfig {

		@Bean
		FakeKakaoOidcTokenVerifier kakaoOidcTokenVerifier() {
			return new FakeKakaoOidcTokenVerifier();
		}

		@Bean
		JwtTokenProvider jwtTokenProvider() {
			JwtProperties properties = new JwtProperties(
				"test-jwt-secret-must-be-at-least-32-bytes",
				Duration.ofMinutes(30),
				Duration.ofDays(14)
			);
			return new JwtTokenProvider(properties);
		}
	}

	static class FakeKakaoOidcTokenVerifier implements KakaoOidcTokenVerifier {

		private KakaoOidcUser user;
		private String lastIdToken;
		private String lastNonce;

		@Override
		public KakaoOidcUser verify(String idToken, String nonce) {
			this.lastIdToken = idToken;
			this.lastNonce = nonce;
			return user;
		}
	}
}
