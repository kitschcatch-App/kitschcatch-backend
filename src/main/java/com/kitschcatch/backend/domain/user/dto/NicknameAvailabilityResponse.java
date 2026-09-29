// 닉네임 중복 확인에 사용한 정규화 결과와 사용 가능 여부를 반환한다.
package com.kitschcatch.backend.domain.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "닉네임 사용 가능 여부")
public record NicknameAvailabilityResponse(
	@Schema(description = "앞뒤 공백 제거·NFC 정규화된 닉네임", example = "키치캐처")
	String nickname,
	@Schema(description = "다른 등록 사용자가 점유하지 않았는지", example = "true")
	boolean available
) {
}
