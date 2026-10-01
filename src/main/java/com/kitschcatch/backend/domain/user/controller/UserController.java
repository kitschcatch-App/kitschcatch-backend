// 내 정보 조회와 프로필 등록·수정 HTTP API를 제공한다.
package com.kitschcatch.backend.domain.user.controller;

import com.kitschcatch.backend.domain.user.dto.RegisterUserProfileRequest;
import com.kitschcatch.backend.domain.user.dto.NicknameAvailabilityResponse;
import com.kitschcatch.backend.domain.user.dto.CreateProfileImageUploadUrlRequest;
import com.kitschcatch.backend.domain.user.dto.ProfileImageUploadUrlResponse;
import com.kitschcatch.backend.domain.user.dto.UpdateUserProfileRequest;
import com.kitschcatch.backend.domain.user.dto.UserMeResponse;
import com.kitschcatch.backend.domain.user.dto.UsernameAvailabilityResponse;
import com.kitschcatch.backend.domain.user.service.UserService;
import com.kitschcatch.backend.domain.user.service.UserProfileImageService;
import com.kitschcatch.backend.global.response.ApiResponse;
import com.kitschcatch.backend.global.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/users")
@Tag(name = "사용자", description = "내 정보와 프로필 관리 API")
@SecurityRequirement(name = "bearerAuth")
public class UserController {

	private final UserService userService;
	private final UserProfileImageService profileImageService;

	public UserController(UserService userService, UserProfileImageService profileImageService) {
		this.userService = userService;
		this.profileImageService = profileImageService;
	}

	@PostMapping("/me/profile/image/presigned-url")
	@Operation(summary = "프로필 이미지 업로드 URL 발급", description = "프로필 등록 전후 모두 사용합니다. JPEG·PNG·WebP, 기본 최대 5MB, 유효기간 10분입니다. 반환된 헤더와 파일 바이트로 PUT한 후 imageKey로 프로필을 저장합니다.")
	public ApiResponse<ProfileImageUploadUrlResponse> createProfileImageUploadUrl(
		@AuthenticationPrincipal AuthenticatedUser user,
		@Valid @RequestBody CreateProfileImageUploadUrlRequest request
	) {
		return ApiResponse.success(profileImageService.createUploadUrl(user.userId(), request));
	}

	@GetMapping("/me")
	@Operation(summary = "내 정보 조회")
	public ApiResponse<UserMeResponse> getMe(@AuthenticationPrincipal AuthenticatedUser user) {
		return ApiResponse.success(userService.getMe(user.userId()));
	}

	@GetMapping("/nickname-availability")
	@Operation(summary = "닉네임 중복 확인", description = "프로필 등록·수정 전에 사용합니다. 본인 닉네임은 사용 가능하며 조회 결과는 닉네임을 예약하지 않습니다. 저장 시 중복되면 USER_004가 반환될 수 있습니다.")
	public ApiResponse<NicknameAvailabilityResponse> checkNicknameAvailability(
		@AuthenticationPrincipal AuthenticatedUser user,
		@Parameter(description = "확인할 닉네임. 앞뒤 공백 제거·NFC 정규화 후 1~50자", required = true)
		@RequestParam String nickname
	) {
		return ApiResponse.success(userService.checkNicknameAvailability(user.userId(), nickname));
	}

	@GetMapping("/username-availability")
	@Operation(summary = "사용자 아이디 중복 확인", description = "본인 아이디는 사용 가능합니다. 조회는 예약하지 않으며 저장 시 중복되면 USER_008을 반환합니다.")
	public ApiResponse<UsernameAvailabilityResponse> checkUsernameAvailability(
		@AuthenticationPrincipal AuthenticatedUser user,
		@Parameter(description = "앞뒤 공백 제거·소문자 변환 후 영문, 숫자, 밑줄 3~30자", required = true)
		@RequestParam String username
	) {
		return ApiResponse.success(userService.checkUsernameAvailability(user.userId(), username));
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
