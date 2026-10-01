// 사용자 아이디의 정규화 결과와 본인 제외 중복 여부를 반환한다.
package com.kitschcatch.backend.domain.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;

public record UsernameAvailabilityResponse(
	@Schema(description = "앞뒤 공백 제거 및 소문자 변환한 아이디", example = "collector_1") String username,
	@Schema(description = "본인을 제외한 사용자에게 점유되지 않은지. 예약은 하지 않습니다.") boolean available
) {
}
