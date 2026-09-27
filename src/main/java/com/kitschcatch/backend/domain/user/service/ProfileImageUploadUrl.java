// 프로필 이미지 업로드에 필요한 서명 URL과 전송 조건을 전달한다.
package com.kitschcatch.backend.domain.user.service;

import java.time.Instant;
import java.util.Map;

public record ProfileImageUploadUrl(
	String uploadUrl,
	String imageKey,
	String imageUrl,
	Instant expiresAt,
	long expiresIn,
	Map<String, String> uploadHeaders
) {
	public ProfileImageUploadUrl {
		uploadHeaders = Map.copyOf(uploadHeaders);
	}
}
