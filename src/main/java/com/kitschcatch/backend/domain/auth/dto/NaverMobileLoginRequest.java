// 네이버 인증 코드와 서버 state 입력을 검증한다.
package com.kitschcatch.backend.domain.auth.dto;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record NaverMobileLoginRequest(@NotBlank @Size(max = 2048) String authorizationCode,
	@NotBlank @Size(min = 43, max = 43) String state) {}
