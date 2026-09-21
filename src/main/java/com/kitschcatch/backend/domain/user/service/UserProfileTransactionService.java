// 외부 이미지 검증을 마친 프로필 변경을 짧은 사용자 행 잠금 트랜잭션으로 저장한다.
package com.kitschcatch.backend.domain.user.service;

import com.kitschcatch.backend.domain.user.entity.User;
import com.kitschcatch.backend.domain.user.repository.UserRepository;
import com.kitschcatch.backend.global.exception.BusinessException;
import com.kitschcatch.backend.global.exception.ErrorCode;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserProfileTransactionService {

	private final UserRepository userRepository;

	public UserProfileTransactionService(UserRepository userRepository) {
		this.userRepository = userRepository;
	}

	@Transactional
	public User registerProfile(Long userId, String nickname, String profileImageKey) {
		User user = findUserForUpdate(userId);
		if (user.isProfileRegistered()) {
			throw new BusinessException(ErrorCode.USER_PROFILE_ALREADY_REGISTERED);
		}
		validateNicknameAvailability(nickname, userId);
		user.registerProfile(nickname, nickname, profileImageKey, Instant.now());
		userRepository.saveAndFlush(user);
		return user;
	}

	@Transactional
	public User updateProfile(Long userId, String nickname, boolean nicknameProvided,
		String profileImageKey, boolean imageProvided) {
		User user = findUserForUpdate(userId);
		if (!user.isProfileRegistered()) {
			throw new BusinessException(ErrorCode.USER_PROFILE_NOT_REGISTERED);
		}
		String updatedNickname = user.getNickname();
		if (nicknameProvided) {
			validateNicknameAvailability(nickname, userId);
			updatedNickname = nickname;
		}
		user.updateProfile(updatedNickname, updatedNickname, profileImageKey, imageProvided);
		userRepository.saveAndFlush(user);
		return user;
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
}
