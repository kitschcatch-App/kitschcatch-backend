// 네이버와 Apple 모바일 인증 HTTP 계약을 제공한다.
package com.kitschcatch.backend.domain.auth.controller;

import com.kitschcatch.backend.domain.auth.dto.*;
import com.kitschcatch.backend.domain.auth.service.SocialAuthService;
import com.kitschcatch.backend.global.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
@Tag(name = "인증")
public class SocialAuthController {
	private final SocialAuthService auth;
	public SocialAuthController(SocialAuthService auth) { this.auth = auth; }

	@PostMapping("/apple/nonce")
	@Operation(summary = "Apple nonce 발급", description = "300초 유효한 일회용 nonce를 발급합니다. Apple 인증 요청 nonce에 원문을 전달하세요.")
	public ApiResponse<KakaoNonceResponse> appleNonce() { return ApiResponse.success(auth.appleNonce()); }

	@PostMapping("/naver/state")
	@Operation(summary = "네이버 인증 요청 발급", description = "서버 state와 모바일 브라우저에서 열 인증 URL을 반환합니다. SDK access token 직접 전달은 지원하지 않습니다.")
	public ApiResponse<NaverAuthorizationResponse> naverState() { return ApiResponse.success(auth.naverAuthorization()); }

	@PostMapping("/apple/mobile-login")
	@Operation(summary = "Apple 모바일 로그인", description = "ID 토큰과 서버 nonce를 검증하고 서비스 토큰 및 프로필 등록 여부를 반환합니다.")
	public ApiResponse<AuthTokenResponse> appleLogin(@Valid @RequestBody AppleMobileLoginRequest request) {
		return ApiResponse.success(auth.appleLogin(request.idToken(), request.nonce()));
	}

	@PostMapping("/naver/mobile-login")
	@Operation(summary = "네이버 모바일 로그인", description = "모바일 브라우저 콜백의 인증 코드와 서버 state를 검증합니다.")
	public ApiResponse<AuthTokenResponse> naverLogin(@Valid @RequestBody NaverMobileLoginRequest request) {
		return ApiResponse.success(auth.naverLogin(request.authorizationCode(), request.state()));
	}
}
