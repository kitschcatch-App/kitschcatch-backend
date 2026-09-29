package com.kitschcatch.backend.domain.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "인증 사용자 응답")
public record AuthUserResponse(
	@Schema(description = "사용자 ID")
	Long id,

	@Schema(description = "사용자 이메일")
	String email,

	@Schema(description = "사용자 닉네임")
	String nickname,

	@Schema(description = "프로필 등록 완료 여부. 최초 프로필 등록 시각이 있으면 true")
	boolean profileRegistered
) {
}
