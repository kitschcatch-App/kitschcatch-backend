// 프로필 이미지 발급과 저장에서 파일 형식·크기·사용자별 키 규칙을 공유한다.
package com.kitschcatch.backend.domain.user.service;

import com.kitschcatch.backend.global.exception.BusinessException;
import com.kitschcatch.backend.global.exception.ErrorCode;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
@EnableConfigurationProperties(ProfileImageProperties.class)
public class ProfileImagePolicy {

	private static final String PREFIX = "profiles/";
	private static final Map<String, String> EXTENSIONS = Map.of(
		"image/jpeg", ".jpg", "image/png", ".png", "image/webp", ".webp");
	private final ProfileImageProperties properties;

	public ProfileImagePolicy(ProfileImageProperties properties) {
		this.properties = properties;
	}

	public String validateUpload(String originalFileName, String contentType, long contentLength) {
		try {
			return validateUploadImage(originalFileName, contentType, contentLength);
		} catch (BusinessException exception) {
			throw new BusinessException(ErrorCode.INVALID_INPUT_VALUE, "파일명·형식·용량이 올바르지 않습니다.");
		}
	}

	private String validateUploadImage(String originalFileName, String contentType, long contentLength) {
		String normalizedType = normalizeContentType(contentType);
		validateLength(contentLength);
		if (!StringUtils.hasText(originalFileName) || originalFileName.length() > 255
			|| originalFileName.contains("/") || originalFileName.contains("\\")
			|| originalFileName.codePoints().anyMatch(Character::isISOControl)
			|| !EXTENSIONS.get(normalizedType).equals(extension(originalFileName.strip()))) {
			throw invalidImage();
		}
		return normalizedType;
	}

	public String createObjectKey(Long userId, String normalizedContentType) {
		if (userId == null || userId <= 0) {
			throw invalidImage();
		}
		return PREFIX + userId + "/" + UUID.randomUUID() + EXTENSIONS.get(normalizeContentType(normalizedContentType));
	}

	public boolean isOwnedKey(Long userId, String objectKey) {
		if (userId == null || userId <= 0 || !StringUtils.hasText(objectKey) || objectKey.length() > 512
			|| !objectKey.startsWith(PREFIX + userId + "/") || objectKey.contains("..")) {
			return false;
		}
		String fileName = objectKey.substring((PREFIX + userId + "/").length());
		return !fileName.contains("/") && !fileName.contains("\\") && !fileName.contains("%")
			&& !fileName.contains("?") && !fileName.contains("#")
			&& fileName.codePoints().noneMatch(Character::isISOControl)
			&& EXTENSIONS.containsValue(extension(fileName));
	}

	public void validateOwnedKey(Long userId, String objectKey) {
		if (objectKey == null || !objectKey.startsWith(PREFIX)) {
			throw invalidImage();
		}
		int separator = objectKey.indexOf('/', PREFIX.length());
		if (separator < 0) {
			throw invalidImage();
		}
		Long owner;
		try {
			owner = Long.valueOf(objectKey.substring(PREFIX.length(), separator));
		} catch (NumberFormatException exception) {
			throw invalidImage();
		}
		if (!isOwnedKey(owner, objectKey)) {
			throw invalidImage();
		}
		if (!owner.equals(userId)) {
			throw new BusinessException(ErrorCode.USER_PROFILE_IMAGE_FORBIDDEN);
		}
	}

	public void validateMetadata(String objectKey, String contentType, long contentLength) {
		String normalizedType = normalizeContentType(contentType);
		validateLength(contentLength);
		if (!EXTENSIONS.get(normalizedType).equals(extension(objectKey))) {
			throw invalidImage();
		}
	}

	private String normalizeContentType(String contentType) {
		if (!StringUtils.hasText(contentType) || contentType.length() > 100) {
			throw invalidImage();
		}
		String normalized = contentType.strip().toLowerCase(Locale.ROOT);
		if (!EXTENSIONS.containsKey(normalized)) {
			throw invalidImage();
		}
		return normalized;
	}

	private void validateLength(long length) {
		if (length < 1 || length > properties.maxSizeBytes()) {
			throw invalidImage();
		}
	}

	private String extension(String name) {
		if (name == null) {
			return "";
		}
		int dot = name.lastIndexOf('.');
		if (dot <= name.lastIndexOf('/') + 1) {
			return "";
		}
		String extension = name.substring(dot).toLowerCase(Locale.ROOT);
		return ".jpeg".equals(extension) ? ".jpg" : extension;
	}

	private BusinessException invalidImage() {
		return new BusinessException(ErrorCode.USER_PROFILE_IMAGE_INVALID);
	}
}
