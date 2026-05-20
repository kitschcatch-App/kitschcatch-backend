package com.kitschcatch.backend.domain.auth.dto;

public record AuthUserResponse(
	Long id,
	String email,
	String nickname
) {
}
