// 내 정보 조회와 사용자 프로필 등록·수정을 처리한다.
package com.kitschcatch.backend.domain.user.service;

import com.kitschcatch.backend.domain.user.dto.RegisterUserProfileRequest;
import com.kitschcatch.backend.domain.user.dto.NicknameAvailabilityResponse;
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

@Service
public class UserService {

	private final UserRepository userRepository;
	private final NicknamePolicy nicknamePolicy;
	private final ProfileImageStorage profileImageStorage;
	private final UserProfileTransactionService transactionService;
	private final ProfileImagePolicy profileImagePolicy;

	public UserService(
		UserRepository userRepository,
		NicknamePolicy nicknamePolicy,
		ProfileImageStorage profileImageStorage,
		UserProfileTransactionService transactionService,
		ProfileImagePolicy profileImagePolicy
	) {
		this.userRepository = userRepository;
		this.nicknamePolicy = nicknamePolicy;
		this.profileImageStorage = profileImageStorage;
		this.transactionService = transactionService;
		this.profileImagePolicy = profileImagePolicy;
	}

	@Transactional(readOnly = true)
	public UserMeResponse getMe(Long userId) {
		return toResponse(findUser(userId));
	}

	@Transactional(readOnly = true)
	public NicknameAvailabilityResponse checkNicknameAvailability(Long userId, String nickname) {
		String normalizedNickname = nicknamePolicy.normalize(nickname);
		findUser(userId);
		boolean available = !userRepository.existsByNicknameKeyAndIdNot(normalizedNickname, userId);
		return new NicknameAvailabilityResponse(normalizedNickname, available);
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
		profileImagePolicy.validateOwnedKey(userId, profileImageKey);
		ProfileImageMetadata metadata = profileImageStorage.metadata(profileImageKey)
			.orElseThrow(() -> new BusinessException(ErrorCode.USER_PROFILE_IMAGE_NOT_UPLOADED));
		profileImagePolicy.validateMetadata(profileImageKey, metadata.contentType(), metadata.contentLength());
		return profileImageKey;
	}

	private UserMeResponse toResponse(User user) {
		String imageUrl = user.getProfileImageKey() == null
			? null
			: profileImageStorage.imageUrl(user.getProfileImageKey());
		return UserMeResponse.from(user, imageUrl);
	}
}
