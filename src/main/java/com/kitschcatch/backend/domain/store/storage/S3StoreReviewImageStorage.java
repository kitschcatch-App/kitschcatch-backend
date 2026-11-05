// 비공개 리뷰 사진의 조건부 업로드와 제한된 읽기, 버전까지 포함한 삭제를 수행한다.
package com.kitschcatch.backend.domain.store.storage;

import com.kitschcatch.backend.domain.post.storage.S3Properties;
import com.kitschcatch.backend.domain.store.service.StoreReviewImageStorage;
import com.kitschcatch.backend.global.exception.BusinessException;
import com.kitschcatch.backend.global.exception.ErrorCode;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.awscore.AwsRequestOverrideConfiguration;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.*;

@Component
@RequiredArgsConstructor
public class S3StoreReviewImageStorage implements StoreReviewImageStorage {
    private final S3Client client;
    private final S3Presigner presigner;
    private final S3Properties properties;
    private static final AwsRequestOverrideConfiguration TIMEOUT = AwsRequestOverrideConfiguration.builder()
        .apiCallTimeout(Duration.ofSeconds(20)).apiCallAttemptTimeout(Duration.ofSeconds(8)).build();

    @Override
    public Upload upload(String key, String contentType, long size) {
        validate(key);
        var request = PutObjectRequest.builder().bucket(properties.bucket()).key(key).contentType(contentType)
            .contentLength(size).ifNoneMatch("*").build();
        var signed = presigner.presignPutObject(PutObjectPresignRequest.builder()
            .signatureDuration(Duration.ofMinutes(5)).putObjectRequest(request).build());
        var headers = new HashMap<String, String>();
        signed.signedHeaders().forEach((name, values) -> {
            if (!name.equalsIgnoreCase("host")) {
                headers.put(name, String.join(",", values));
            }
        });
        return new Upload(signed.url().toString(), signed.expiration(), headers);
    }

    @Override
    public byte[] read(String key, String contentType, long expectedSize) {
        validate(key);
        try (var stream = client.getObject(GetObjectRequest.builder().bucket(properties.bucket()).key(key)
                .overrideConfiguration(TIMEOUT).build())) {
            if (!Long.valueOf(expectedSize).equals(stream.response().contentLength())
                    || !contentType.equals(stream.response().contentType())) {
                stream.abort();
                throw new BusinessException(ErrorCode.STORE_REVIEW_IMAGE_INVALID);
            }
            byte[] bytes = stream.readNBytes((int) expectedSize + 1);
            if (bytes.length != expectedSize) {
                stream.abort();
                throw new BusinessException(ErrorCode.STORE_REVIEW_IMAGE_INVALID);
            }
            return bytes;
        } catch (S3Exception error) {
            if (error.statusCode() == 404) {
                throw new BusinessException(ErrorCode.STORE_REVIEW_IMAGE_NOT_UPLOADED);
            }
            throw new BusinessException(ErrorCode.STORE_REVIEW_IMAGE_STORAGE_UNAVAILABLE);
        } catch (IOException | SdkException error) {
            throw new BusinessException(ErrorCode.STORE_REVIEW_IMAGE_STORAGE_UNAVAILABLE);
        }
    }

    @Override
    public void publish(String key, byte[] png) {
        validate(key);
        String digest = sha256(png);
        try {
            try {
                client.putObject(PutObjectRequest.builder().bucket(properties.bucket()).key(key)
                    .contentType("image/png").contentLength((long) png.length).ifNoneMatch("*")
                    .metadata(Map.of("sha256", digest)).overrideConfiguration(TIMEOUT).build(), RequestBody.fromBytes(png));
            } catch (S3Exception error) {
                if (error.statusCode() != 412) {
                    throw error;
                }
                var existing = client.headObject(HeadObjectRequest.builder().bucket(properties.bucket()).key(key)
                    .overrideConfiguration(TIMEOUT).build());
                if (!digest.equals(existing.metadata().get("sha256")) || existing.contentLength() != png.length
                        || !"image/png".equals(existing.contentType())) {
                    throw new BusinessException(ErrorCode.STORE_REVIEW_IMAGE_INVALID);
                }
            }
        } catch (SdkException error) {
            throw new BusinessException(ErrorCode.STORE_REVIEW_IMAGE_STORAGE_UNAVAILABLE);
        }
    }

    @Override
    public String imageUrl(String key) {
        validate(key);
        return presigner.presignGetObject(GetObjectPresignRequest.builder()
            .signatureDuration(properties.downloadUrlTtl())
            .getObjectRequest(GetObjectRequest.builder().bucket(properties.bucket()).key(key).build())
            .build()).url().toString();
    }

    @Override
    public void delete(String key) {
        validate(key);
        // 삭제 마커만 추가하면 버전 버킷의 원본 사진이 남으므로 정확한 키의 모든 버전을 지운다.
        for (int page = 0; page < 10; page++) {
            var result = client.listObjectVersions(ListObjectVersionsRequest.builder()
                .bucket(properties.bucket()).prefix(key).maxKeys(100).overrideConfiguration(TIMEOUT).build());
            var versions = result.versions().stream().filter(version -> version.key().equals(key)).toList();
            var markers = result.deleteMarkers().stream().filter(marker -> marker.key().equals(key)).toList();
            if (versions.isEmpty() && markers.isEmpty()) {
                return;
            }
            for (var version : versions) {
                deleteVersion(key, version.versionId());
            }
            for (var marker : markers) {
                deleteVersion(key, marker.versionId());
            }
        }
        throw new BusinessException(ErrorCode.STORE_REVIEW_IMAGE_STORAGE_UNAVAILABLE);
    }

    private void deleteVersion(String key, String versionId) {
        client.deleteObject(DeleteObjectRequest.builder().bucket(properties.bucket()).key(key)
            .versionId(versionId).overrideConfiguration(TIMEOUT).build());
    }

    private void validate(String key) {
        if (properties.bucket() == null || properties.bucket().isBlank()) {
            throw new BusinessException(ErrorCode.S3_BUCKET_NOT_CONFIGURED);
        }
        if (key == null || !key.matches(
                "(store-review-uploads/[1-9][0-9]*/[0-9a-f-]{36}|store-review-images/[1-9][0-9]*/[1-9][0-9]*/[0-9a-f-]{36}[.]png)")) {
            throw new BusinessException(ErrorCode.STORE_REVIEW_IMAGE_INVALID);
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
