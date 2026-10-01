// Apple 모바일 로그인 입력을 검증한다.
package com.kitschcatch.backend.domain.auth.dto;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AppleMobileLoginRequest(@NotBlank @Size(max = 16384) String idToken,
	@NotBlank @Size(min = 43, max = 43) String nonce) {}
