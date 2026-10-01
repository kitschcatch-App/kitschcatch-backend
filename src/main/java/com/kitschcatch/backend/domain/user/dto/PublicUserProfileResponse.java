// 공개 조회에서 계정 이메일과 인증 식별자를 제외한 프로필만 반환한다.
package com.kitschcatch.backend.domain.user.dto;

import com.kitschcatch.backend.domain.user.entity.User;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

@Schema(description = "등록된 사용자의 공개 프로필")
public record PublicUserProfileResponse(
	Long id,
	String nickname,
	String username,
	String bio,
	String profileImageUrl,
	Instant profileRegisteredAt
) {
	public static PublicUserProfileResponse from(User user, String imageUrl) {
		return new PublicUserProfileResponse(user.getId(), user.getNickname(), user.getUsername(),
			user.getBio(), imageUrl, user.getProfileRegisteredAt());
	}
}
