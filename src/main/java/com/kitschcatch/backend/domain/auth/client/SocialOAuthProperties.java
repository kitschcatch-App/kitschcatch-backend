// 네이버와 Apple의 서버 신뢰 설정을 제공한다.
package com.kitschcatch.backend.domain.auth.client;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "social.oauth")
public record SocialOAuthProperties(Naver naver, Apple apple) {
	public record Naver(String clientId, String clientSecret, String redirectUri,
		String authorizeUri, String tokenUri, String userInfoUri) {}
	public record Apple(String clientId, String jwkSetUri) {}
}
