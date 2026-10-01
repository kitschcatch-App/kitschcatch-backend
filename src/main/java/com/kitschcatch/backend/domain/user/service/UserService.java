// 내 정보 조회와 사용자 프로필 등록·수정을 처리한다.
package com.kitschcatch.backend.domain.user.service;

import com.kitschcatch.backend.domain.user.dto.RegisterUserProfileRequest;
import com.kitschcatch.backend.domain.user.dto.NicknameAvailabilityResponse;
import com.kitschcatch.backend.domain.user.dto.UpdateUserProfileRequest;
import com.kitschcatch.backend.domain.user.dto.UserMeResponse;
import com.kitschcatch.backend.domain.user.dto.PublicUserProfileResponse;
import com.kitschcatch.backend.domain.user.dto.UsernameAvailabilityResponse;
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
	private final PublicProfilePolicy publicProfilePolicy;

	public UserService(
		UserRepository userRepository,
		NicknamePolicy nicknamePolicy,
		ProfileImageStorage profileImageStorage,
		UserProfileTransactionService transactionService,
		ProfileImagePolicy profileImagePolicy,
		PublicProfilePolicy publicProfilePolicy
	) {
		this.userRepository = userRepository;
		this.nicknamePolicy = nicknamePolicy;
		this.profileImageStorage = profileImageStorage;
		this.transactionService = transactionService;
		this.profileImagePolicy = profileImagePolicy;
		this.publicProfilePolicy = publicProfilePolicy;
	}

	@Transactional(readOnly = true)
	public PublicUserProfileResponse getPublicProfile(Long userId) {
		if (userId == null || userId <= 0) {
			throw new BusinessException(ErrorCode.BAD_REQUEST, "사용자 ID는 양의 정수여야 합니다.");
		}
		User user = findUser(userId);
		if (!user.isProfileRegistered()) {
			throw new BusinessException(ErrorCode.USER_NOT_FOUND);
		}
		String imageUrl = user.getProfileImageKey() == null ? null : profileImageStorage.imageUrl(user.getProfileImageKey());
		return PublicUserProfileResponse.from(user, imageUrl);
	}

	@Transactional(readOnly = true)
	public UsernameAvailabilityResponse checkUsernameAvailability(Long userId, String username) {
		String normalized = publicProfilePolicy.normalizeUsername(username);
		if (normalized == null) {
			throw new BusinessException(ErrorCode.INVALID_INPUT_VALUE, "확인할 사용자 아이디가 필요합니다.");
		}
		findUser(userId);
		return new UsernameAvailabilityResponse(normalized, !userRepository.existsByUsernameAndIdNot(normalized, userId));
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
		String username = publicProfilePolicy.normalizeUsername(request.username());
		String bio = publicProfilePolicy.normalizeBio(request.bio());
		try {
			return toResponse(transactionService.registerProfile(userId, nickname, profileImageKey, username, bio));
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
		String username = request.usernameProvided() ? publicProfilePolicy.normalizeUsername(request.username()) : null;
		String bio = request.bioProvided() ? publicProfilePolicy.normalizeBio(request.bio()) : null;
		try {
			return toResponse(transactionService.updateProfile(
				userId, nickname, request.nicknameProvided(), profileImageKey, request.profileImageKeyProvided(),
				username, request.usernameProvided(), bio, request.bioProvided()));
		} catch (DataIntegrityViolationException exception) {
			throw translateConstraintViolation(exception);
		}
	}

	private RuntimeException translateConstraintViolation(DataIntegrityViolationException exception) {
		for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
			if (cause instanceof ConstraintViolationException violation
				&& isUsernameUniqueViolation(violation)) {
				return new BusinessException(ErrorCode.USER_USERNAME_DUPLICATED);
			}
			if (cause instanceof ConstraintViolationException violation
				&& "uk_users_nickname_key".equals(violation.getConstraintName())) {
				return new BusinessException(ErrorCode.USER_NICKNAME_DUPLICATED);
			}
		}
		return exception;
	}

	private boolean isUsernameUniqueViolation(ConstraintViolationException violation) {
		String constraint = violation.getConstraintName();
		if (!"23505".equals(violation.getSQLState()) || constraint == null) {
			return false;
		}
		// H2는 제약 이름 뒤에 INDEX 설명을 붙이고 스키마를 포함한다.
		String name = constraint.split(" INDEX ", 2)[0];
		name = name.substring(name.lastIndexOf('.') + 1);
		return "uk_users_username".equalsIgnoreCase(name);
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
