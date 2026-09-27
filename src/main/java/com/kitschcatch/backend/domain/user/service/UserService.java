// 내 정보 조회와 사용자 프로필 등록·수정을 처리한다.
package com.kitschcatch.backend.domain.user.service;

import com.kitschcatch.backend.domain.user.dto.RegisterUserProfileRequest;
import com.kitschcatch.backend.domain.user.dto.UpdateUserProfileRequest;
import com.kitschcatch.backend.domain.user.dto.UserMeResponse;
import com.kitschcatch.backend.domain.user.entity.User;
import com.kitschcatch.backend.domain.user.repository.UserRepository;
import com.kitschcatch.backend.global.exception.BusinessException;
import com.kitschcatch.backend.global.exception.ErrorCode;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class UserService {

	private final UserRepository userRepository;
	private final NicknamePolicy nicknamePolicy;
	private final ProfileImageStorage profileImageStorage;
	private final UserProfileTransactionService transactionService;

	public UserService(
		UserRepository userRepository,
		NicknamePolicy nicknamePolicy,
		ProfileImageStorage profileImageStorage,
		UserProfileTransactionService transactionService
	) {
		this.userRepository = userRepository;
		this.nicknamePolicy = nicknamePolicy;
		this.profileImageStorage = profileImageStorage;
		this.transactionService = transactionService;
	}

	@Transactional(readOnly = true)
	public UserMeResponse getMe(Long userId) {
		return toResponse(findUser(userId));
	}

	public UserMeResponse registerProfile(Long userId, RegisterUserProfileRequest request) {
		String nickname = nicknamePolicy.normalize(request.nickname());
		User user = findUser(userId);
		if (user.isProfileRegistered()) {
			throw new BusinessException(ErrorCode.USER_PROFILE_ALREADY_REGISTERED);
		}
		String profileImageKey = validateProfileImage(userId, request.profileImageKey());
		try {
			return toResponse(transactionService.registerProfile(userId, nickname, profileImageKey));
		} catch (DataIntegrityViolationException exception) {
			throw translateConstraintViolation(exception);
		}
	}

	public UserMeResponse updateProfile(Long userId, UpdateUserProfileRequest request) {
		if (!request.hasChanges()) {
			throw new BusinessException(ErrorCode.BAD_REQUEST, "변경할 프로필 필드가 없습니다.");
		}
		User user = findUser(userId);
		if (!user.isProfileRegistered()) {
			throw new BusinessException(ErrorCode.USER_PROFILE_NOT_REGISTERED);
		}

		String nickname = request.nicknameProvided() ? nicknamePolicy.normalize(request.nickname()) : null;
		String profileImageKey = request.profileImageKeyProvided()
			? validateProfileImage(userId, request.profileImageKey()) : null;
		try {
			return toResponse(transactionService.updateProfile(
				userId, nickname, request.nicknameProvided(), profileImageKey, request.profileImageKeyProvided()));
		} catch (DataIntegrityViolationException exception) {
			throw translateConstraintViolation(exception);
		}
	}

	private RuntimeException translateConstraintViolation(DataIntegrityViolationException exception) {
		for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
			if (cause instanceof ConstraintViolationException violation
				&& "uk_users_nickname_key".equals(violation.getConstraintName())) {
				return new BusinessException(ErrorCode.USER_NICKNAME_DUPLICATED);
			}
		}
		return exception;
	}

	private User findUser(Long userId) {
		return userRepository.findById(userId)
			.orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
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
