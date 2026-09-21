// 인증 사용자의 계정 정보와 프로필 상태를 표현한다.
package com.kitschcatch.backend.domain.user.dto;

import com.kitschcatch.backend.domain.user.entity.User;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

@Schema(description = "내 정보 응답")
public record UserMeResponse(
	@Schema(description = "사용자 ID") Long id,
	@Schema(description = "사용자 이메일") String email,
	@Schema(description = "사용자 닉네임") String nickname,
	@Schema(description = "프로필 이미지 object key") String profileImageKey,
	@Schema(description = "프로필 이미지 URL") String profileImageUrl,
	@Schema(description = "프로필 등록 시각") Instant profileRegisteredAt
) {

	public static UserMeResponse from(User user, String profileImageUrl) {
		return new UserMeResponse(
			user.getId(),
			user.getEmail(),
			user.getNickname(),
			user.getProfileImageKey(),
			profileImageUrl,
			user.getProfileRegisteredAt()
		);
	}
}
