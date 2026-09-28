// 프로필 이미지의 S3 업로드 URL 발급과 객체 메타데이터 조회를 담당한다.
package com.kitschcatch.backend.domain.user.storage;

import com.kitschcatch.backend.domain.post.storage.S3Properties;
import com.kitschcatch.backend.domain.user.service.ProfileImagePolicy;
import com.kitschcatch.backend.domain.user.service.ProfileImageMetadata;
import com.kitschcatch.backend.domain.user.service.ProfileImageProperties;
import com.kitschcatch.backend.domain.user.service.ProfileImageStorage;
import com.kitschcatch.backend.domain.user.service.ProfileImageUploadUrl;
import com.kitschcatch.backend.global.exception.BusinessException;
import com.kitschcatch.backend.global.exception.ErrorCode;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

@Component
public class S3ProfileImageStorage implements ProfileImageStorage {

	private final S3Client s3Client;
	private final S3Properties properties;
	private final S3Presigner presigner;
	private final ProfileImagePolicy policy;
	private final ProfileImageProperties profileProperties;

	public S3ProfileImageStorage(S3Client s3Client, S3Properties properties, S3Presigner presigner,
		ProfileImagePolicy policy, ProfileImageProperties profileProperties) {
		this.s3Client = s3Client;
		this.properties = properties;
		this.presigner = presigner;
		this.policy = policy;
		this.profileProperties = profileProperties;
	}

	@Override
	public ProfileImageUploadUrl createUploadUrl(Long userId, String fileName, String contentType, long fileSize) {
		String normalizedType = policy.validateUpload(fileName, contentType, fileSize);
		validateBucket();
		String key = policy.createObjectKey(userId, normalizedType);
		var request = PutObjectRequest.builder()
			.bucket(properties.bucket()).key(key).contentType(normalizedType)
			.contentLength(fileSize).ifNoneMatch("*").build();
		var signed = presigner.presignPutObject(PutObjectPresignRequest.builder()
			.signatureDuration(profileProperties.uploadUrlTtl()).putObjectRequest(request).build());
		Map<String, String> headers = new HashMap<>();
		signed.signedHeaders().forEach((name, values) -> {
			// Host와 Content-Length는 HTTP 클라이언트가 URL과 파일 바이트로 설정한다.
			if (!name.equalsIgnoreCase("host") && !name.equalsIgnoreCase("content-length")) {
				headers.put(name, String.join(",", values));
			}
		});
		return new ProfileImageUploadUrl(signed.url().toString(), key, imageUrl(key), signed.expiration(),
			profileProperties.uploadUrlTtl().toSeconds(), headers);
	}

	@Override
	public Optional<ProfileImageMetadata> metadata(String objectKey) {
		validateBucket();
		try {
			var head = s3Client.headObject(HeadObjectRequest.builder().bucket(properties.bucket()).key(objectKey).build());
			return Optional.of(new ProfileImageMetadata(head.contentType(), head.contentLength() == null ? 0 : head.contentLength()));
		} catch (NoSuchKeyException exception) {
			return Optional.empty();
		} catch (S3Exception exception) {
			if (exception.statusCode() == 404) {
				return Optional.empty();
			}
			throw exception;
		}
	}

	@Override
	public String imageUrl(String objectKey) {
		String path;
		try {
			path = new URI(null, null, "/" + objectKey, null).toASCIIString();
		} catch (URISyntaxException exception) {
			throw new BusinessException(ErrorCode.USER_PROFILE_IMAGE_INVALID);
		}
		if (StringUtils.hasText(properties.publicBaseUrl())) {
			return properties.publicBaseUrl() + path;
		}
		validateBucket();
		return "https://" + properties.bucket() + ".s3." + properties.region() + ".amazonaws.com" + path;
	}

	private void validateBucket() {
		if (!StringUtils.hasText(properties.bucket())) {
			throw new BusinessException(ErrorCode.S3_BUCKET_NOT_CONFIGURED);
		}
	}
}
