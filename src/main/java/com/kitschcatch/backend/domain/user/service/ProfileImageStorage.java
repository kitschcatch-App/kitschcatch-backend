// 프로필 이미지 업로드 발급과 저장 객체 조회 경계를 정의한다.
package com.kitschcatch.backend.domain.user.service;

import java.util.Optional;

public interface ProfileImageStorage {

	ProfileImageUploadUrl createUploadUrl(Long userId, String fileName, String contentType, long fileSize);

	Optional<ProfileImageMetadata> metadata(String objectKey);

	String imageUrl(String objectKey);
}
