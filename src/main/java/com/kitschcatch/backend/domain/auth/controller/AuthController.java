package com.kitschcatch.backend.domain.auth.controller;

import com.kitschcatch.backend.domain.auth.dto.AuthTokenResponse;
import com.kitschcatch.backend.domain.auth.dto.KakaoMobileLoginRequest;
import com.kitschcatch.backend.domain.auth.dto.KakaoNonceResponse;
import com.kitschcatch.backend.domain.auth.dto.LogoutRequest;
import com.kitschcatch.backend.domain.auth.dto.TokenRefreshRequest;
import com.kitschcatch.backend.domain.auth.service.AuthService;
import com.kitschcatch.backend.global.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
@Tag(name = "인증", description = "카카오 로그인, 토큰 재발급, 로그아웃 API")
public class AuthController {

	private final AuthService authService;

	public AuthController(AuthService authService) {
		this.authService = authService;
	}

	@PostMapping("/kakao/nonce")
	@Operation(summary = "카카오 로그인 nonce 발급", description = "카카오 모바일 로그인에서 ID 토큰 검증에 사용할 nonce를 발급합니다.")
	public ApiResponse<KakaoNonceResponse> createKakaoLoginNonce() {
		return ApiResponse.success(authService.createKakaoLoginNonce());
	}

	@PostMapping("/kakao/mobile-login")
	@Operation(summary = "카카오 모바일 로그인", description = "카카오 SDK에서 받은 ID 토큰과 nonce로 로그인하고 access token과 refresh token을 발급합니다.")
	public ApiResponse<AuthTokenResponse> loginWithKakaoIdToken(
		@Valid @RequestBody KakaoMobileLoginRequest request
	) {
		return ApiResponse.success(authService.loginWithKakaoIdToken(request.idToken(), request.nonce()));
	}

	@PostMapping("/token/refresh")
	@Operation(summary = "토큰 재발급", description = "유효한 refresh token으로 새 access token과 refresh token을 발급합니다.")
	public ApiResponse<AuthTokenResponse> refresh(@Valid @RequestBody TokenRefreshRequest request) {
		return ApiResponse.success(authService.refresh(request.refreshToken()));
	}

	@PostMapping("/logout")
	@Operation(summary = "로그아웃", description = "refresh token을 폐기해 해당 토큰으로 재발급할 수 없게 합니다.")
	public ApiResponse<Void> logout(@Valid @RequestBody LogoutRequest request) {
		authService.logout(request.refreshToken());
		return ApiResponse.success(null);
	}
}
