package com.kitschcatch.backend.domain.auth.controller;

import com.kitschcatch.backend.domain.auth.dto.AuthTokenResponse;
import com.kitschcatch.backend.domain.auth.dto.KakaoMobileLoginRequest;
import com.kitschcatch.backend.domain.auth.dto.KakaoNonceResponse;
import com.kitschcatch.backend.domain.auth.dto.LogoutRequest;
import com.kitschcatch.backend.domain.auth.dto.TokenRefreshRequest;
import com.kitschcatch.backend.domain.auth.service.AuthService;
import com.kitschcatch.backend.global.response.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

	private final AuthService authService;

	public AuthController(AuthService authService) {
		this.authService = authService;
	}

	@PostMapping("/kakao/nonce")
	public ApiResponse<KakaoNonceResponse> createKakaoLoginNonce() {
		return ApiResponse.success(authService.createKakaoLoginNonce());
	}

	@PostMapping("/kakao/mobile-login")
	public ApiResponse<AuthTokenResponse> loginWithKakaoIdToken(
		@Valid @RequestBody KakaoMobileLoginRequest request
	) {
		return ApiResponse.success(authService.loginWithKakaoIdToken(request.idToken(), request.nonce()));
	}

	@PostMapping("/token/refresh")
	public ApiResponse<AuthTokenResponse> refresh(@Valid @RequestBody TokenRefreshRequest request) {
		return ApiResponse.success(authService.refresh(request.refreshToken()));
	}

	@PostMapping("/logout")
	public ApiResponse<Void> logout(@Valid @RequestBody LogoutRequest request) {
		authService.logout(request.refreshToken());
		return ApiResponse.success(null);
	}
}
