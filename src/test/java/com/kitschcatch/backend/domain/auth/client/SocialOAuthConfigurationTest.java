// 미설정 소셜 제공자는 외부 요청 없이 명확한 오류로 실패하는지 검증한다.
package com.kitschcatch.backend.domain.auth.client;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import com.kitschcatch.backend.domain.auth.repository.LoginNonceRepository;
import com.kitschcatch.backend.domain.auth.service.AuthService;
import com.kitschcatch.backend.domain.auth.service.SocialAuthService;
import com.kitschcatch.backend.domain.auth.service.SocialUserService;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import com.kitschcatch.backend.domain.auth.oidc.AppleOidcTokenVerifier;
import com.kitschcatch.backend.global.exception.BusinessException;
import com.kitschcatch.backend.global.exception.ErrorCode;
import org.junit.jupiter.api.Test;

class SocialOAuthConfigurationTest {
	private SocialOAuthProperties emptyConfiguration() {
		return new SocialOAuthProperties(new SocialOAuthProperties.Naver("", "", "", "https://nid.naver.com/oauth2.0/authorize",
			"https://nid.naver.com/oauth2.0/token", "https://openapi.naver.com/v1/nid/me"),
			new SocialOAuthProperties.Apple("", "https://appleid.apple.com/auth/keys"));
	}
	@Test
	void missingNaverCredentialsFailBeforeAuthorizationOrExchange() {
		var verifier = new NaverTokenVerifier(emptyConfiguration());
		assertThatThrownBy(() -> verifier.authorizationUrl("state")).isInstanceOf(BusinessException.class)
			.satisfies(e -> org.assertj.core.api.Assertions.assertThat(((BusinessException) e).getErrorCode())
				.isEqualTo(ErrorCode.SOCIAL_LOGIN_NOT_CONFIGURED));
		assertThatThrownBy(() -> verifier.verify("code", "state")).isInstanceOf(BusinessException.class);
	}
	@ParameterizedTest
	@ValueSource(ints = {0, 1, 2, 3, 4, 5})
	void missingNaverSettingFailsBeforeSavingOrConsumingState(int missing) {
		String[] settings = {"client", "secret", "https://app.example/callback"};
		settings[missing % 3] = missing < 3 ? "" : "  ";
		var properties = new SocialOAuthProperties(new SocialOAuthProperties.Naver(settings[0], settings[1],
			settings[2], "https://nid.naver.com/oauth2.0/authorize", "https://nid.naver.com/oauth2.0/token",
			"https://openapi.naver.com/v1/nid/me"), emptyConfiguration().apple());
		var nonces = mock(LoginNonceRepository.class);
		var users = mock(SocialUserService.class);
		var auth = mock(AuthService.class);
		var service = new SocialAuthService(new NaverTokenVerifier(properties), null, nonces, users, auth);

		assertThatThrownBy(service::naverAuthorization).isInstanceOfSatisfying(BusinessException.class,
			e -> org.assertj.core.api.Assertions.assertThat(e.getErrorCode()).isEqualTo(ErrorCode.SOCIAL_LOGIN_NOT_CONFIGURED));
		assertThatThrownBy(() -> service.naverLogin("code", "state")).isInstanceOfSatisfying(BusinessException.class,
			e -> org.assertj.core.api.Assertions.assertThat(e.getErrorCode()).isEqualTo(ErrorCode.SOCIAL_LOGIN_NOT_CONFIGURED));
		verifyNoInteractions(nonces, users, auth);
	}

	@Test
	void missingAppleClientIdFailsBeforeJwksRequest() {
		var verifier = new AppleOidcTokenVerifier(emptyConfiguration());
		assertThatThrownBy(() -> verifier.verify("token", "nonce")).isInstanceOf(BusinessException.class)
			.satisfies(e -> org.assertj.core.api.Assertions.assertThat(((BusinessException) e).getErrorCode())
				.isEqualTo(ErrorCode.SOCIAL_LOGIN_NOT_CONFIGURED));
	}
}
