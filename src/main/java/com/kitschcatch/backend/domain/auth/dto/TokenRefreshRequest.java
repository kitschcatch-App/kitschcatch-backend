package com.kitschcatch.backend.domain.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

@Schema(description = "토큰 재발급 요청")
public record TokenRefreshRequest(
	@Schema(description = "토큰 재발급에 사용할 refresh token")
	@NotBlank(message = "리프레시 토큰은 필수입니다.")
	String refreshToken
) {
}
