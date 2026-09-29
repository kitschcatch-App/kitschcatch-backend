// 사용자 프로필 상태와 저장소 검증 규칙을 검증한다.
package com.kitschcatch.backend.domain.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.kitschcatch.backend.domain.user.dto.RegisterUserProfileRequest;
import com.kitschcatch.backend.domain.user.dto.UpdateUserProfileRequest;
import com.kitschcatch.backend.domain.user.dto.UserMeResponse;
import com.kitschcatch.backend.domain.user.entity.AuthProvider;
import com.kitschcatch.backend.domain.user.entity.User;
import com.kitschcatch.backend.domain.user.repository.UserRepository;
import com.kitschcatch.backend.global.exception.BusinessException;
import com.kitschcatch.backend.global.exception.ErrorCode;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class UserServiceTest {

	private UserRepository userRepository;
	private ProfileImageStorage profileImageStorage;
	private UserService userService;
	private User user;

	@BeforeEach
	void setUp() {
		userRepository = org.mockito.Mockito.mock(UserRepository.class);
		profileImageStorage = org.mockito.Mockito.mock(ProfileImageStorage.class);
		userService = new UserService(userRepository, new NicknamePolicy(), profileImageStorage,
			new UserProfileTransactionService(userRepository),
			new ProfileImagePolicy(new ProfileImageProperties(5_000_000, java.time.Duration.ofMinutes(10))));
		user = User.builder()
			.nickname("kakao-default")
			.email("user@example.com")
			.authProvider(AuthProvider.KAKAO)
			.providerUserId("provider-1")
			.build();
		ReflectionTestUtils.setField(user, "id", 1L);
		when(userRepository.findById(1L)).thenReturn(Optional.of(user));
	}

	@Test
	void getMeReturnsUnregisteredUserWithNullProfileValues() {
		when(userRepository.findById(1L)).thenReturn(Optional.of(user));

		UserMeResponse response = userService.getMe(1L);

		assertThat(response.id()).isEqualTo(1L);
		assertThat(response.nickname()).isEqualTo("kakao-default");
		assertThat(response.profileImageKey()).isNull();
		assertThat(response.profileRegisteredAt()).isNull();
		verify(profileImageStorage, never()).imageUrl(org.mockito.ArgumentMatchers.anyString());
	}

	@Test
	void availabilityUsesNormalizedNicknameAndExcludesAuthenticatedUser() {
		user.registerProfile("가", "가", null, java.time.Instant.now());
		when(userRepository.existsByNicknameKeyAndIdNot("가", 1L)).thenReturn(false);

		var response = userService.checkNicknameAvailability(1L, " 가 ");

		assertThat(response.nickname()).isEqualTo("가");
		assertThat(response.available()).isTrue();
		verify(userRepository).existsByNicknameKeyAndIdNot("가", 1L);
		verify(userRepository, never()).findByIdForUpdate(1L);
		verifyNoInteractions(profileImageStorage);
	}

	@Test
	void availabilityReportsAnotherUsersNicknameAsUnavailable() {
		when(userRepository.existsByNicknameKeyAndIdNot("collector", 1L)).thenReturn(true);

		var response = userService.checkNicknameAvailability(1L, "collector");

		assertThat(response.available()).isFalse();
		verifyNoInteractions(profileImageStorage);
	}

	@Test
	void availabilityRejectsInvalidNicknameAndMissingUserBeforeQuery() {
		assertThatThrownBy(() -> userService.checkNicknameAvailability(1L, " "))
			.isInstanceOf(BusinessException.class)
			.extracting("errorCode").isEqualTo(ErrorCode.INVALID_INPUT_VALUE);
		when(userRepository.findById(1L)).thenReturn(Optional.empty());
		assertThatThrownBy(() -> userService.checkNicknameAvailability(1L, "valid"))
			.isInstanceOf(BusinessException.class)
			.extracting("errorCode").isEqualTo(ErrorCode.USER_NOT_FOUND);
		verify(userRepository, never()).existsByNicknameKeyAndIdNot(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyLong());
	}

	@Test
	void registerProfileStoresNicknameAndImageAfterValidatingUploadedObject() {
		when(userRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(user));
		when(userRepository.existsByNicknameKeyAndIdNot("collector", 1L)).thenReturn(false);
		when(profileImageStorage.metadata("profiles/1/image.png")).thenReturn(Optional.of(new ProfileImageMetadata("image/png", 1024)));
		when(profileImageStorage.imageUrl("profiles/1/image.png")).thenReturn("https://cdn/profiles/1/image.png");
		when(userRepository.saveAndFlush(user)).thenReturn(user);

		UserMeResponse response = userService.registerProfile(
			1L,
			new RegisterUserProfileRequest(" collector ", "profiles/1/image.png")
		);

		assertThat(user.isProfileRegistered()).isTrue();
		assertThat(user.getNickname()).isEqualTo("collector");
		assertThat(user.getNicknameKey()).isEqualTo("collector");
		assertThat(user.getProfileImageKey()).isEqualTo("profiles/1/image.png");
		assertThat(response.profileImageUrl()).isEqualTo("https://cdn/profiles/1/image.png");
	}

	@Test
	void duplicateProfileRegistrationIsRejectedWithoutOverwritingUser() {
		user.registerProfile("collector", "collector", null, java.time.Instant.now());
		when(userRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(user));

		assertThatThrownBy(() -> userService.registerProfile(1L, new RegisterUserProfileRequest("other", null)))
			.isInstanceOf(BusinessException.class)
			.extracting("errorCode")
			.isEqualTo(ErrorCode.USER_PROFILE_ALREADY_REGISTERED);
		assertThat(user.getNickname()).isEqualTo("collector");
	}

	@Test
	void updateProfileDistinguishesImageNullFromImageOmission() {
		user.registerProfile("collector", "collector", "profiles/1/old.png", java.time.Instant.now());
		when(userRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(user));
		when(userRepository.existsByNicknameKeyAndIdNot("new-name", 1L)).thenReturn(false);

		UpdateUserProfileRequest request = new UpdateUserProfileRequest();
		request.setNickname("new-name");
		request.setProfileImageKey(null);
		userService.updateProfile(1L, request);

		assertThat(user.getNickname()).isEqualTo("new-name");
		assertThat(user.getProfileImageKey()).isNull();
	}

	@Test
	void updateWithoutFieldsIsRejected() {
		UpdateUserProfileRequest request = new UpdateUserProfileRequest();

		assertThatThrownBy(() -> userService.updateProfile(1L, request))
			.isInstanceOf(BusinessException.class)
			.extracting("errorCode")
			.isEqualTo(ErrorCode.BAD_REQUEST);
		verify(userRepository, never()).findByIdForUpdate(1L);
	}
}
