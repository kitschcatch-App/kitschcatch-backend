// 클라이언트에 프로필 이미지 업로드 URL과 만료 시각 및 필수 헤더를 반환한다.
package com.kitschcatch.backend.domain.user.dto;

import com.kitschcatch.backend.domain.user.service.ProfileImageUploadUrl;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.Map;

public record ProfileImageUploadUrlResponse(
	@Schema(description = "파일 바이트를 PUT할 서명 URL. 백엔드 JWT를 보내지 않습니다.") String uploadUrl,
	@Schema(description = "업로드 성공 후 프로필 API의 profileImageKey에 전달할 키") String imageKey,
	@Schema(description = "업로드 이후 조회 URL. 실제 접근에는 버킷 또는 CDN 설정이 필요합니다.") String imageUrl,
	@Schema(description = "서명의 만료 시각. 임시 AWS 자격 증명이 먼저 만료되면 사용 불가합니다.") Instant expiresAt,
	@Schema(description = "발급 시점부터 유효한 초. 기본 600초", example = "600") long expiresIn,
	@Schema(description = "PUT에 그대로 사용할 헤더. Content-Length는 요청 fileSize와 같은 파일 바이트로 자동 설정합니다.")
	Map<String, String> uploadHeaders
) {
	public static ProfileImageUploadUrlResponse from(ProfileImageUploadUrl result) {
		return new ProfileImageUploadUrlResponse(result.uploadUrl(), result.imageKey(), result.imageUrl(),
			result.expiresAt(), result.expiresIn(), result.uploadHeaders());
	}
}
