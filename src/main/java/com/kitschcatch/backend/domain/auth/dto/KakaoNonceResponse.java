package com.kitschcatch.backend.domain.auth.dto;

public record KakaoNonceResponse(
	String nonce,
	long expiresIn
) {
}
