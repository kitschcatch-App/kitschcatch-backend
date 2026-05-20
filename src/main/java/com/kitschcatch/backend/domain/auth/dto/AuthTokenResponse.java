package com.kitschcatch.backend.domain.auth.dto;

public record AuthTokenResponse(
	String tokenType,
	String accessToken,
	long expiresIn,
	String refreshToken,
	long refreshTokenExpiresIn,
	AuthUserResponse user
) {
}
