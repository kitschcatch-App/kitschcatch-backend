// 프로필 이미지의 소유권과 저장 객체 검증 경계를 정의한다.
package com.kitschcatch.backend.domain.user.service;

public interface ProfileImageStorage {

	boolean isOwnedProfileImageKey(Long userId, String objectKey);

	boolean exists(String objectKey);

	String imageUrl(String objectKey);
}
