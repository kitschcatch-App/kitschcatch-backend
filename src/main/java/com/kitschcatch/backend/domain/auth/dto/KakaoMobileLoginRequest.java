package com.kitschcatch.backend.domain.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

@Schema(description = "카카오 모바일 로그인 요청")
public record KakaoMobileLoginRequest(
	@Schema(description = "카카오 SDK에서 발급받은 ID 토큰")
	@NotBlank(message = "ID 토큰은 필수입니다.")
	String idToken,

	@Schema(description = "서버가 발급한 카카오 로그인 nonce")
	@NotBlank(message = "nonce는 필수입니다.")
	String nonce
) {
}
