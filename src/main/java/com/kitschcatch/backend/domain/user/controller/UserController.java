// 내 정보 조회와 프로필 등록·수정 HTTP API를 제공한다.
package com.kitschcatch.backend.domain.user.controller;

import com.kitschcatch.backend.domain.user.dto.RegisterUserProfileRequest;
import com.kitschcatch.backend.domain.user.dto.UpdateUserProfileRequest;
import com.kitschcatch.backend.domain.user.dto.UserMeResponse;
import com.kitschcatch.backend.domain.user.service.UserService;
import com.kitschcatch.backend.global.response.ApiResponse;
import com.kitschcatch.backend.global.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/users")
@Tag(name = "사용자", description = "내 정보와 프로필 관리 API")
@SecurityRequirement(name = "bearerAuth")
public class UserController {

	private final UserService userService;

	public UserController(UserService userService) {
		this.userService = userService;
	}

	@GetMapping("/me")
	@Operation(summary = "내 정보 조회")
	public ApiResponse<UserMeResponse> getMe(@AuthenticationPrincipal AuthenticatedUser user) {
		return ApiResponse.success(userService.getMe(user.userId()));
	}

	@PostMapping("/me/profile")
	@Operation(summary = "프로필 등록")
	public ApiResponse<UserMeResponse> registerProfile(
		@AuthenticationPrincipal AuthenticatedUser user,
		@Valid @RequestBody RegisterUserProfileRequest request
	) {
		return ApiResponse.created(userService.registerProfile(user.userId(), request));
	}

	@PatchMapping("/me")
	@Operation(summary = "프로필 수정")
	public ApiResponse<UserMeResponse> updateProfile(
		@AuthenticationPrincipal AuthenticatedUser user,
		@Valid @RequestBody UpdateUserProfileRequest request
	) {
		return ApiResponse.success(userService.updateProfile(user.userId(), request));
	}
}
