// 최초 사용자 프로필 등록 요청의 필수값과 이미지 키를 검증한다.
package com.kitschcatch.backend.domain.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "사용자 프로필 등록 요청")
@JsonIgnoreProperties(ignoreUnknown = false)
public record RegisterUserProfileRequest(
	@Schema(description = "사용자 닉네임. 앞뒤 공백 제거·NFC 정규화 후 1~50자", example = "키치수집가")
	@NotBlank
	String nickname,

	@Schema(description = "프로필 이미지 object key", example = "profiles/42/image.png")
	@Size(max = 512)
	String profileImageKey
) {
}
