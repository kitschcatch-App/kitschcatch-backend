package com.kitschcatch.backend.domain.post.storage;

import com.kitschcatch.backend.domain.post.service.PostImageStorage;
import com.kitschcatch.backend.domain.post.service.PostImageUploadUrl;
import com.kitschcatch.backend.global.exception.BusinessException;
import com.kitschcatch.backend.global.exception.ErrorCode;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

@Component
public class S3PostImageStorage implements PostImageStorage {

	private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of(
		"image/jpeg",
		"image/png",
		"image/webp",
		"image/gif"
	);
	private static final Map<String, String> EXTENSIONS_BY_CONTENT_TYPE = Map.of(
		"image/jpeg", ".jpg",
		"image/png", ".png",
		"image/webp", ".webp",
		"image/gif", ".gif"
	);

	private final S3Presigner s3Presigner;
	private final S3Client s3Client;
	private final S3Properties properties;

	public S3PostImageStorage(S3Presigner s3Presigner, S3Client s3Client, S3Properties properties) {
		this.s3Presigner = s3Presigner;
		this.s3Client = s3Client;
		this.properties = properties;
	}

	@Override
	public PostImageUploadUrl createUploadUrl(Long userId, String originalFileName, String contentType) {
		validateBucket();
		String normalizedContentType = normalizeContentType(contentType);
		String objectKey = objectKey(userId, originalFileName, normalizedContentType);
		PutObjectRequest putObjectRequest = PutObjectRequest.builder()
			.bucket(properties.bucket())
			.key(objectKey)
			.contentType(normalizedContentType)
			.build();
		PutObjectPresignRequest presignRequest = PutObjectPresignRequest.builder()
			.signatureDuration(properties.uploadUrlTtl())
			.putObjectRequest(putObjectRequest)
			.build();
		PresignedPutObjectRequest presignedRequest = s3Presigner.presignPutObject(presignRequest);

		return new PostImageUploadUrl(
			presignedRequest.url().toString(),
			objectKey,
			imageUrl(objectKey),
			properties.uploadUrlTtl().toSeconds()
		);
	}

	@Override
	public boolean isOwnedPostImageKey(Long userId, String objectKey) {
		if (!StringUtils.hasText(objectKey) || objectKey.contains("..")) {
			return false;
		}
		return objectKey.startsWith(properties.postImagePrefix() + "/" + userId + "/");
	}

	@Override
	public boolean exists(String objectKey) {
		validateBucket();
		try {
			s3Client.headObject(HeadObjectRequest.builder()
				.bucket(properties.bucket())
				.key(objectKey)
				.build());
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

	private String objectKey(Long userId, String originalFileName, String contentType) {
		return properties.postImagePrefix() + "/" + userId + "/" + UUID.randomUUID() + extension(originalFileName, contentType);
	}

	private String extension(String originalFileName, String contentType) {
		String extension = extensionFromFileName(originalFileName);
		if (StringUtils.hasText(extension)) {
			return extension;
		}
		return EXTENSIONS_BY_CONTENT_TYPE.get(contentType);
	}

	private String extensionFromFileName(String originalFileName) {
		if (!StringUtils.hasText(originalFileName)) {
			return "";
		}
		String lowerCaseFileName = originalFileName.toLowerCase(Locale.ROOT);
		int dotIndex = lowerCaseFileName.lastIndexOf('.');
		if (dotIndex < 0) {
			return "";
		}
		String extension = lowerCaseFileName.substring(dotIndex);
		if (EXTENSIONS_BY_CONTENT_TYPE.containsValue(extension) || ".jpeg".equals(extension)) {
			return ".jpeg".equals(extension) ? ".jpg" : extension;
		}
		return "";
	}

	private String normalizeContentType(String contentType) {
		if (!StringUtils.hasText(contentType)) {
			throw new BusinessException(ErrorCode.POST_IMAGE_INVALID);
		}
		String normalizedContentType = contentType.toLowerCase(Locale.ROOT);
		if (!ALLOWED_CONTENT_TYPES.contains(normalizedContentType)) {
			throw new BusinessException(ErrorCode.POST_IMAGE_INVALID);
		}
		return normalizedContentType;
	}

	private void validateBucket() {
		if (!StringUtils.hasText(properties.bucket())) {
			throw new BusinessException(ErrorCode.S3_BUCKET_NOT_CONFIGURED);
		}
	}
}
