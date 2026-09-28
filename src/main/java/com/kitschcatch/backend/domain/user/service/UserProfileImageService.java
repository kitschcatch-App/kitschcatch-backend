// 인증된 사용자의 존재를 확인하고 프로필 상태 변경 없이 업로드 URL을 발급한다.
package com.kitschcatch.backend.domain.user.service;

import com.kitschcatch.backend.domain.user.dto.CreateProfileImageUploadUrlRequest;
import com.kitschcatch.backend.domain.user.dto.ProfileImageUploadUrlResponse;
import com.kitschcatch.backend.domain.user.repository.UserRepository;
import com.kitschcatch.backend.global.exception.BusinessException;
import com.kitschcatch.backend.global.exception.ErrorCode;
import org.springframework.stereotype.Service;

@Service
public class UserProfileImageService {
	private final UserRepository userRepository;
	private final ProfileImageStorage storage;

	public UserProfileImageService(UserRepository userRepository, ProfileImageStorage storage) {
		this.userRepository = userRepository;
		this.storage = storage;
	}

	public ProfileImageUploadUrlResponse createUploadUrl(Long userId, CreateProfileImageUploadUrlRequest request) {
		if (!userRepository.existsById(userId)) {
			throw new BusinessException(ErrorCode.USER_NOT_FOUND);
		}
		return ProfileImageUploadUrlResponse.from(storage.createUploadUrl(userId,
			request.fileName(), request.contentType(), request.fileSize()));
	}
}
