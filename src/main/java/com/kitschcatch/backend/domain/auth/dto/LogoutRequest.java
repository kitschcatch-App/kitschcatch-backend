package com.kitschcatch.backend.domain.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

@Schema(description = "로그아웃 요청")
public record LogoutRequest(
	@Schema(description = "폐기할 refresh token")
	@NotBlank(message = "리프레시 토큰은 필수입니다.")
	String refreshToken
) {
}
