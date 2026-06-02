package com.kitschcatch.backend.domain.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "인증 토큰 발급 응답")
public record AuthTokenResponse(
	@Schema(description = "토큰 타입")
	String tokenType,

	@Schema(description = "API 인증에 사용할 access token")
	String accessToken,

	@Schema(description = "access token 만료까지 남은 초")
	long expiresIn,

	@Schema(description = "토큰 재발급에 사용할 refresh token")
	String refreshToken,

	@Schema(description = "refresh token 만료까지 남은 초")
	long refreshTokenExpiresIn,

	@Schema(description = "로그인한 사용자 정보")
	AuthUserResponse user
) {
}
