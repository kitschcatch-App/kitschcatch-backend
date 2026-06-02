package com.kitschcatch.backend.domain.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "카카오 로그인 nonce 발급 응답")
public record KakaoNonceResponse(
	@Schema(description = "카카오 ID 토큰 검증에 사용할 nonce")
	String nonce,

	@Schema(description = "nonce 만료까지 남은 초")
	long expiresIn
) {
}
