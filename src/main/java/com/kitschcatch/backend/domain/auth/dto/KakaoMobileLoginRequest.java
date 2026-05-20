package com.kitschcatch.backend.domain.auth.dto;

import jakarta.validation.constraints.NotBlank;

public record KakaoMobileLoginRequest(
	@NotBlank(message = "ID 토큰은 필수입니다.")
	String idToken,

	@NotBlank(message = "nonce는 필수입니다.")
	String nonce
) {
}
