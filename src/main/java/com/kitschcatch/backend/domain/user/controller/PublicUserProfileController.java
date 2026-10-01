// 로그인 사용자가 본인 또는 타인의 공개 프로필만 조회하도록 별도 API를 제공한다.
package com.kitschcatch.backend.domain.user.controller;

import com.kitschcatch.backend.domain.user.dto.PublicUserProfileResponse;
import com.kitschcatch.backend.domain.user.service.UserService;
import com.kitschcatch.backend.global.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/users")
@Tag(name = "공개 프로필", description = "계정 정보가 제외된 사용자 프로필 조회")
@SecurityRequirement(name = "bearerAuth")
public class PublicUserProfileController {
	private final UserService userService;

	public PublicUserProfileController(UserService userService) {
		this.userService = userService;
	}

	@GetMapping("/{userId}")
	@Operation(summary = "공개 회원 프로필 조회", description = "본인과 타인에게 같은 공개 필드를 반환합니다. 미등록 또는 없는 사용자는 USER_001을 반환합니다.")
	public ApiResponse<PublicUserProfileResponse> getProfile(@PathVariable Long userId) {
		return ApiResponse.success(userService.getPublicProfile(userId));
	}
}
