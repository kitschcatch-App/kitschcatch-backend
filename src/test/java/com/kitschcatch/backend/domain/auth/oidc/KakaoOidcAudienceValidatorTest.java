package com.kitschcatch.backend.domain.auth.oidc;

import static org.assertj.core.api.Assertions.assertThat;

import com.kitschcatch.backend.domain.auth.client.KakaoOAuthProperties;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;

class KakaoOidcAudienceValidatorTest {

	@Test
	@DisplayName("Native app key만 ID 토큰 audience로 허용한다")
	void audienceValidatorAllowsOnlyNativeAppKey() {
		OAuth2TokenValidator<Jwt> validator = KakaoOidcAudienceValidator.from(new KakaoOAuthProperties(
			"native-app-key",
			"https://kauth.kakao.com",
			"https://kauth.kakao.com/.well-known/jwks.json"
		));

		assertThat(validator.validate(jwtWithAudience("native-app-key")).hasErrors()).isFalse();
		assertThat(validator.validate(jwtWithAudience("rest-api-key")).hasErrors()).isTrue();
		assertThat(validator.validate(jwtWithAudience("other-app-key")).hasErrors()).isTrue();
	}

	private Jwt jwtWithAudience(String audience) {
		Instant now = Instant.now();
		return new Jwt(
			"token",
			now,
			now.plusSeconds(3600),
			java.util.Map.of("alg", "RS256"),
			java.util.Map.of(
				"sub", "123456789",
				"aud", List.of(audience)
			)
		);
	}
}
