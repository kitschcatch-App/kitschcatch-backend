package com.kitschcatch.backend.domain.auth.client;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class KakaoOAuthPropertiesTest {

	@Test
	@DisplayName("Native app key가 없으면 설정 생성을 거부한다")
	void nativeAppKeyIsRequired() {
		assertThatThrownBy(() -> new KakaoOAuthProperties(
			"",
			"https://kauth.kakao.com",
			"https://kauth.kakao.com/.well-known/jwks.json"
		))
			.isInstanceOf(IllegalArgumentException.class);
	}
}
