// 내 정보 조회와 사용자 프로필 등록·수정을 처리한다.
package com.kitschcatch.backend.domain.user.service;

import com.kitschcatch.backend.domain.user.dto.RegisterUserProfileRequest;
import com.kitschcatch.backend.domain.user.dto.UpdateUserProfileRequest;
import com.kitschcatch.backend.domain.user.dto.UserMeResponse;
import com.kitschcatch.backend.domain.user.entity.User;
import com.kitschcatch.backend.domain.user.repository.UserRepository;
import com.kitschcatch.backend.global.exception.BusinessException;
import com.kitschcatch.backend.global.exception.ErrorCode;
import java.time.Instant;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class UserService {

	private final UserRepository userRepository;
	private final NicknamePolicy nicknamePolicy;
	private final ProfileImageStorage profileImageStorage;

	public UserService(
		UserRepository userRepository,
		NicknamePolicy nicknamePolicy,
		ProfileImageStorage profileImageStorage
	) {
		this.userRepository = userRepository;
		this.nicknamePolicy = nicknamePolicy;
		this.profileImageStorage = profileImageStorage;
	}

	@Transactional(readOnly = true)
	public UserMeResponse getMe(Long userId) {
		return toResponse(findUser(userId));
	}

	@Transactional
	public UserMeResponse registerProfile(Long userId, RegisterUserProfileRequest request) {
		String nickname = nicknamePolicy.normalize(request.nickname());
		User user = findUserForUpdate(userId);
		if (user.isProfileRegistered()) {
			throw new BusinessException(ErrorCode.USER_PROFILE_ALREADY_REGISTERED);
		}
		validateNicknameAvailability(nickname, userId);
		String profileImageKey = validateProfileImage(userId, request.profileImageKey());
		try {
			user.registerProfile(nickname, nickname, profileImageKey, Instant.now());
			userRepository.saveAndFlush(user);
		} catch (DataIntegrityViolationException exception) {
			throw new BusinessException(ErrorCode.USER_NICKNAME_DUPLICATED);
		}
		return toResponse(user);
	}

	@Transactional
	public UserMeResponse updateProfile(Long userId, UpdateUserProfileRequest request) {
		if (!request.hasChanges()) {
			throw new BusinessException(ErrorCode.BAD_REQUEST, "변경할 프로필 필드가 없습니다.");
		}
		User user = findUserForUpdate(userId);
		if (!user.isProfileRegistered()) {
			throw new BusinessException(ErrorCode.USER_PROFILE_NOT_REGISTERED);
		}

		String nickname = user.getNickname();
		if (request.nicknameProvided()) {
			nickname = nicknamePolicy.normalize(request.nickname());
			validateNicknameAvailability(nickname, userId);
		}
		String profileImageKey = user.getProfileImageKey();
		if (request.profileImageKeyProvided()) {
			profileImageKey = validateProfileImage(userId, request.profileImageKey());
		}
		try {
			user.updateProfile(nickname, nickname, profileImageKey, request.profileImageKeyProvided());
			userRepository.saveAndFlush(user);
		} catch (DataIntegrityViolationException exception) {
			throw new BusinessException(ErrorCode.USER_NICKNAME_DUPLICATED);
		}
		return toResponse(user);
	}

	private User findUser(Long userId) {
		return userRepository.findById(userId)
			.orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
	}

	private User findUserForUpdate(Long userId) {
		return userRepository.findByIdForUpdate(userId)
			.orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
	}

	private void validateNicknameAvailability(String nickname, Long userId) {
		if (userRepository.existsByNicknameKeyAndIdNot(nickname, userId)) {
			throw new BusinessException(ErrorCode.USER_NICKNAME_DUPLICATED);
		}
	}

	private String validateProfileImage(Long userId, String profileImageKey) {
		if (profileImageKey == null) {
			return null;
		}
		if (!StringUtils.hasText(profileImageKey)
			|| !profileImageStorage.isOwnedProfileImageKey(userId, profileImageKey)) {
			throw new BusinessException(ErrorCode.USER_PROFILE_IMAGE_INVALID);
		}
		if (!profileImageStorage.exists(profileImageKey)) {
			throw new BusinessException(ErrorCode.USER_PROFILE_IMAGE_NOT_UPLOADED);
		}
		return profileImageKey;
	}

	private UserMeResponse toResponse(User user) {
		String imageUrl = user.getProfileImageKey() == null
			? null
			: profileImageStorage.imageUrl(user.getProfileImageKey());
		return UserMeResponse.from(user, imageUrl);
	}
}
