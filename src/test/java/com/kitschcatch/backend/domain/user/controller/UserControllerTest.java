// 사용자 프로필 API의 인증 주체 전달과 HTTP 계약을 검증한다.
package com.kitschcatch.backend.domain.user.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.kitschcatch.backend.domain.user.dto.RegisterUserProfileRequest;
import com.kitschcatch.backend.domain.user.dto.UpdateUserProfileRequest;
import com.kitschcatch.backend.domain.user.dto.UserMeResponse;
import com.kitschcatch.backend.domain.user.service.UserService;
import com.kitschcatch.backend.global.exception.GlobalExceptionHandler;
import com.kitschcatch.backend.global.response.ResponseStatusSetterAdvice;
import com.kitschcatch.backend.global.security.AuthenticatedUser;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class UserControllerTest {

	private UserService userService;
	private MockMvc mockMvc;
	private UsernamePasswordAuthenticationToken authentication;

	@BeforeEach
	void setUp() {
		userService = mock(UserService.class);
		mockMvc = MockMvcBuilders.standaloneSetup(new UserController(userService))
			.setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
			.setControllerAdvice(new ResponseStatusSetterAdvice(), new GlobalExceptionHandler())
			.build();
		authentication = new UsernamePasswordAuthenticationToken(new AuthenticatedUser(1L), null, List.of());
		SecurityContextHolder.getContext().setAuthentication(authentication);
	}

	@AfterEach
	void tearDown() {
		SecurityContextHolder.clearContext();
	}

	@Test
	void getMeUsesAuthenticatedUserId() throws Exception {
		when(userService.getMe(1L)).thenReturn(response());

		mockMvc.perform(get("/api/users/me").principal(authentication))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.id").value(1))
			.andExpect(jsonPath("$.data.nickname").value("collector"));
	}

	@Test
	void registerProfileReturnsCreatedResponse() throws Exception {
		when(userService.registerProfile(eq(1L), any(RegisterUserProfileRequest.class))).thenReturn(response());

		mockMvc.perform(post("/api/users/me/profile")
				.principal(authentication)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"nickname\":\"collector\"}"))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.data.nickname").value("collector"));
	}

	@Test
	void patchPreservesExplicitNullImageField() throws Exception {
		when(userService.updateProfile(eq(1L), any(UpdateUserProfileRequest.class))).thenAnswer(invocation -> {
			UpdateUserProfileRequest request = invocation.getArgument(1);
			org.assertj.core.api.Assertions.assertThat(request.profileImageKeyProvided()).isTrue();
			org.assertj.core.api.Assertions.assertThat(request.profileImageKey()).isNull();
			return response();
		});

		mockMvc.perform(patch("/api/users/me")
				.principal(authentication)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"profileImageKey\":null}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.id").value(1));
	}

	@Test
	void patchRejectsUnknownField() throws Exception {
		mockMvc.perform(patch("/api/users/me")
				.principal(authentication)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"changed@example.com\"}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("COMMON_002"));
	}

	private UserMeResponse response() {
		return new UserMeResponse(1L, "user@example.com", "collector", null, null, Instant.parse("2026-09-22T00:00:00Z"));
	}
}
