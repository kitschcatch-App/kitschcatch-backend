// S3에 저장된 프로필 이미지의 사용자별 키와 공개 URL을 검증한다.
package com.kitschcatch.backend.domain.user.storage;

import com.kitschcatch.backend.domain.post.storage.S3Properties;
import com.kitschcatch.backend.domain.user.service.ProfileImageStorage;
import com.kitschcatch.backend.global.exception.BusinessException;
import com.kitschcatch.backend.global.exception.ErrorCode;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;

@Component
public class S3ProfileImageStorage implements ProfileImageStorage {

	private static final String PROFILE_IMAGE_PREFIX = "profiles";
	private static final Set<String> ALLOWED_EXTENSIONS = Set.of(".jpg", ".jpeg", ".png", ".webp", ".gif");

	private final S3Client s3Client;
	private final S3Properties properties;

	public S3ProfileImageStorage(S3Client s3Client, S3Properties properties) {
		this.s3Client = s3Client;
		this.properties = properties;
	}

	@Override
	public boolean isOwnedProfileImageKey(Long userId, String objectKey) {
		if (!StringUtils.hasText(objectKey) || objectKey.length() > 512 || objectKey.contains("..")) {
			return false;
		}
		String prefix = PROFILE_IMAGE_PREFIX + "/" + userId + "/";
		String lowerKey = objectKey.toLowerCase(Locale.ROOT);
		int dotIndex = lowerKey.lastIndexOf('.');
		return objectKey.startsWith(prefix)
			&& dotIndex > prefix.length()
			&& ALLOWED_EXTENSIONS.contains(lowerKey.substring(dotIndex));
	}

	@Override
	public boolean exists(String objectKey) {
		validateBucket();
		try {
			s3Client.headObject(HeadObjectRequest.builder().bucket(properties.bucket()).key(objectKey).build());
			return true;
		} catch (NoSuchKeyException exception) {
			return false;
		} catch (S3Exception exception) {
			if (exception.statusCode() == 404) {
				return false;
			}
			throw exception;
		}
	}

	@Override
	public String imageUrl(String objectKey) {
		if (StringUtils.hasText(properties.publicBaseUrl())) {
			return properties.publicBaseUrl() + "/" + objectKey;
		}
		validateBucket();
		return "https://" + properties.bucket() + ".s3." + properties.region() + ".amazonaws.com/" + objectKey;
	}

	private void validateBucket() {
		if (!StringUtils.hasText(properties.bucket())) {
			throw new BusinessException(ErrorCode.S3_BUCKET_NOT_CONFIGURED);
		}
	}
}
