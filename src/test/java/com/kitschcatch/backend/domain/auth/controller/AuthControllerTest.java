package com.kitschcatch.backend.domain.auth.controller;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.kitschcatch.backend.domain.auth.dto.AuthTokenResponse;
import com.kitschcatch.backend.domain.auth.dto.AuthUserResponse;
import com.kitschcatch.backend.domain.auth.dto.KakaoNonceResponse;
import com.kitschcatch.backend.domain.auth.service.AuthService;
import com.kitschcatch.backend.global.exception.BusinessException;
import com.kitschcatch.backend.global.exception.ErrorCode;
import com.kitschcatch.backend.global.exception.GlobalExceptionHandler;
import com.kitschcatch.backend.global.response.ResponseStatusSetterAdvice;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class AuthControllerTest {

	private AuthService authService;
	private MockMvc mockMvc;

	@BeforeEach
	void setUp() {
		authService = mock(AuthService.class);
		mockMvc = MockMvcBuilders.standaloneSetup(new AuthController(authService))
			.setControllerAdvice(new ResponseStatusSetterAdvice(), new GlobalExceptionHandler())
			.build();
	}

	@Test
	@DisplayName("카카오 nonce 발급 API는 앱에서 사용할 nonce를 반환한다")
	void kakaoNonceReturnsNonceResponse() throws Exception {
		when(authService.createKakaoLoginNonce())
			.thenReturn(new KakaoNonceResponse("nonce-value", 300));

		mockMvc.perform(post("/api/auth/kakao/nonce"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.nonce").value("nonce-value"))
			.andExpect(jsonPath("$.data.expiresIn").value(300));
	}

	@Test
	@DisplayName("카카오 모바일 로그인 API는 SDK ID 토큰으로 공통 응답 형식의 토큰을 반환한다")
	void kakaoMobileLoginReturnsTokenResponse() throws Exception {
		when(authService.loginWithKakaoIdToken(anyString(), anyString()))
			.thenReturn(new AuthTokenResponse(
				"Bearer",
				"access-token",
				1800,
				"refresh-token",
				1209600,
				new AuthUserResponse(1L, "kakao@example.com", "kakao")
			));

		mockMvc.perform(post("/api/auth/kakao/mobile-login")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "idToken": "kakao-sdk-id-token",
					  "nonce": "nonce-value"
					}
					"""))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.accessToken").value("access-token"))
			.andExpect(jsonPath("$.data.refreshToken").value("refresh-token"));
	}

	@Test
	@DisplayName("카카오 모바일 로그인 API는 이메일 누락 오류를 공통 에러 형식으로 반환한다")
	void kakaoMobileLoginWithoutEmailReturnsCommonError() throws Exception {
		when(authService.loginWithKakaoIdToken(anyString(), anyString()))
			.thenThrow(new BusinessException(ErrorCode.KAKAO_EMAIL_REQUIRED));

		mockMvc.perform(post("/api/auth/kakao/mobile-login")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "idToken": "kakao-sdk-id-token",
					  "nonce": "nonce-value"
					}
					"""))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.error.code").value("AUTH_002"));
	}

	@Test
	@DisplayName("토큰 재발급 API는 리프레시 토큰을 서비스에 전달한다")
	void refreshTokenDelegatesToService() throws Exception {
		when(authService.refresh("refresh-token"))
			.thenReturn(new AuthTokenResponse(
				"Bearer",
				"new-access-token",
				1800,
				"new-refresh-token",
				1209600,
				new AuthUserResponse(1L, "kakao@example.com", "kakao")
			));

		mockMvc.perform(post("/api/auth/token/refresh")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "refreshToken": "refresh-token"
					}
					"""))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.accessToken").value("new-access-token"))
			.andExpect(jsonPath("$.data.refreshToken").value("new-refresh-token"));
	}

	@Test
	@DisplayName("로그아웃 API는 리프레시 토큰을 폐기한다")
	void logoutDelegatesToService() throws Exception {
		mockMvc.perform(post("/api/auth/logout")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "refreshToken": "refresh-token"
					}
					"""))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true));

		verify(authService).logout("refresh-token");
	}

	@Test
	@DisplayName("잘못된 리프레시 토큰은 공통 에러 형식으로 반환한다")
	void invalidRefreshTokenReturnsCommonError() throws Exception {
		doThrow(new BusinessException(ErrorCode.INVALID_REFRESH_TOKEN))
			.when(authService)
			.logout("bad-refresh-token");

		mockMvc.perform(post("/api/auth/logout")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "refreshToken": "bad-refresh-token"
					}
					"""))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.error.code").value("AUTH_003"));
	}
}
