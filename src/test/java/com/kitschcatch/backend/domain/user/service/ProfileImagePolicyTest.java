// 프로필 이미지의 업로드 입력과 저장 메타데이터 경계값을 검증한다.
package com.kitschcatch.backend.domain.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.kitschcatch.backend.global.exception.BusinessException;
import com.kitschcatch.backend.global.exception.ErrorCode;
import java.time.Duration;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class ProfileImagePolicyTest {

	private final ProfileImagePolicy policy = new ProfileImagePolicy(new ProfileImageProperties(5000000, Duration.ofMinutes(10)));

	@ParameterizedTest
	@CsvSource({"profile.JPG,image/jpeg,.jpg", "profile.jpeg,image/jpeg,.jpg", "profile.png,image/png,.png",
		"profile.webp,image/webp,.webp"})
	void acceptsSupportedTypesAndGeneratesUniqueOwnedKeys(String name, String type, String extension) {
		String normalized = policy.validateUpload(name, " " + type.toUpperCase(Locale.ROOT) + " ", 5000000);
		String key = policy.createObjectKey(42L, normalized);
		assertThat(key).startsWith("profiles/42/").endsWith(extension);
		assertThat(key).isNotEqualTo(policy.createObjectKey(42L, normalized));
		assertThat(policy.isOwnedKey(42L, key)).isTrue();
		policy.validateMetadata(key, type, 1);
	}

	@ParameterizedTest
	@ValueSource(longs = {0, -1, 5000001, Long.MAX_VALUE})
	void rejectsInvalidDeclaredAndActualSize(long length) {
		assertInvalidUpload(() -> policy.validateUpload("image.png", "image/png", length));
		assertInvalid(() -> policy.validateMetadata("profiles/42/image.png", "image/png", length));
	}

	@ParameterizedTest
	@ValueSource(strings = {"image.jpg", "image.txt", "image", ".png", "../image.png", "dir/image.png", "dir\\image.png", "bad\n.png"})
	void rejectsInvalidNameOrExtensionMismatch(String name) {
		assertInvalidUpload(() -> policy.validateUpload(name, "image/png", 1));
	}

	@ParameterizedTest
	@ValueSource(strings = {"profiles/4/image.png", "profiles/420/image.png", "posts/42/image.png", "chats/42/image.png",
		"https://cdn/profiles/42/image.png", "profiles/42/../image.png", "profiles/42/dir/image.png", "profiles/42/.png",
		"profiles/42/image%2F.png", "profiles/42/image?x.png", "profiles/42/image#x.png", "profiles/42/a\\b.png"})
	void rejectsOtherOwnersAndAmbiguousPaths(String key) {
		assertThat(policy.isOwnedKey(42L, key)).isFalse();
	}

	@Test
	void preservesLegacyNamesAndChecksMetadataType() {
		assertThat(policy.isOwnedKey(42L, "profiles/42/image.jpeg")).isTrue();
		assertThat(policy.isOwnedKey(42L, "profiles/42/내 사진.png")).isTrue();
		policy.validateMetadata("profiles/42/image.jpeg", "image/jpeg", 12);
		assertInvalid(() -> policy.validateMetadata("profiles/42/image.png", "image/jpeg", 12));
		assertInvalid(() -> policy.validateMetadata("profiles/42/image.png", "text/plain", 12));
		assertInvalid(() -> policy.validateMetadata("profiles/42/image.png", null, 12));
	}

	@Test
	void rejectsUnsupportedGifAndInvalidConfiguration() {
		assertInvalidUpload(() -> policy.validateUpload("image.gif", "image/gif", 12));
		assertThatThrownBy(() -> new ProfileImageProperties(0, Duration.ofMinutes(10)))
			.isInstanceOf(IllegalArgumentException.class);
		for (Duration ttl : new Duration[] {Duration.ZERO, Duration.ofMillis(500), Duration.ofDays(8)}) {
			assertThatThrownBy(() -> new ProfileImageProperties(5000000, ttl)).isInstanceOf(IllegalArgumentException.class);
		}
	}

	private void assertInvalidUpload(Runnable action) {
		assertThatThrownBy(action::run).isInstanceOf(BusinessException.class)
			.extracting("errorCode").isEqualTo(ErrorCode.INVALID_INPUT_VALUE);
	}

	private void assertInvalid(Runnable action) {
		assertThatThrownBy(action::run).isInstanceOf(BusinessException.class)
			.extracting("errorCode").isEqualTo(ErrorCode.USER_PROFILE_IMAGE_INVALID);
	}
}
