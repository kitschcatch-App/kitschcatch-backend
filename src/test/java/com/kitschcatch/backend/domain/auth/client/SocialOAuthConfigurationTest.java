// 미설정 소셜 제공자는 외부 요청 없이 명확한 오류로 실패하는지 검증한다.
package com.kitschcatch.backend.domain.auth.client;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
	@Test
	void missingAppleClientIdFailsBeforeJwksRequest() {
		var verifier = new AppleOidcTokenVerifier(emptyConfiguration());
		assertThatThrownBy(() -> verifier.verify("token", "nonce")).isInstanceOf(BusinessException.class)
			.satisfies(e -> org.assertj.core.api.Assertions.assertThat(((BusinessException) e).getErrorCode())
				.isEqualTo(ErrorCode.SOCIAL_LOGIN_NOT_CONFIGURED));
	}
}
