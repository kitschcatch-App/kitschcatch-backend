package com.kitschcatch.backend.domain.auth.client;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

@ConfigurationProperties(prefix = "kakao.oauth")
public record KakaoOAuthProperties(
	String nativeAppKey,
	String issuerUri,
	String jwkSetUri
) {

	public KakaoOAuthProperties {
		requireText(nativeAppKey, "kakao.oauth.native-app-key must not be blank.");
		requireText(issuerUri, "kakao.oauth.issuer-uri must not be blank.");
		requireText(jwkSetUri, "kakao.oauth.jwk-set-uri must not be blank.");
	}

	private static void requireText(String value, String message) {
		if (!StringUtils.hasText(value)) {
			throw new IllegalArgumentException(message);
		}
	}
}
