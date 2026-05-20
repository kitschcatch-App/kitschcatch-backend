package com.kitschcatch.backend.domain.auth.oidc;

import com.kitschcatch.backend.domain.auth.client.KakaoOAuthProperties;
import java.util.List;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.util.StringUtils;

public final class KakaoOidcAudienceValidator {

	private KakaoOidcAudienceValidator() {
	}

	public static OAuth2TokenValidator<Jwt> from(KakaoOAuthProperties properties) {
		List<String> allowedAudiences = List.of(properties.nativeAppKey())
			.stream()
			.filter(StringUtils::hasText)
			.toList();

		return token -> {
			if (token.getAudience().stream().anyMatch(allowedAudiences::contains)) {
				return OAuth2TokenValidatorResult.success();
			}
			return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Invalid audience", null));
		};
	}
}
