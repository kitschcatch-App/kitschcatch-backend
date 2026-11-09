// 비공개 사진의 S3 서명, 바이트 검증, 조건부 저장과 버전 삭제를 제공한다.
package com.kitschcatch.backend.global.image;

import com.kitschcatch.backend.domain.post.storage.S3Properties;
import com.kitschcatch.backend.global.exception.BusinessException;
import com.kitschcatch.backend.global.exception.ErrorCode;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.regex.Pattern;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import software.amazon.awssdk.awscore.AwsRequestOverrideConfiguration;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.*;

public class PrivateImageObjects {
    private final S3Client client;
    private final S3Presigner presigner;
    private final S3Properties properties;
    private static final AwsRequestOverrideConfiguration TIMEOUT = AwsRequestOverrideConfiguration.builder()
        .apiCallTimeout(Duration.ofSeconds(20)).apiCallAttemptTimeout(Duration.ofSeconds(8)).build();

    private final Pattern temporaryKeys;
    private final Pattern finalKeys;
    private final Errors errors;

    public record Errors(ErrorCode invalid, ErrorCode notUploaded, ErrorCode unavailable) {}
    public record Upload(String url, Instant expiresAt, Map<String, String> headers) {}
    public record Download(String url, Instant expiresAt) {}

    public PrivateImageObjects(S3Client client, S3Presigner presigner, S3Properties properties,
                               Pattern temporaryKeys, Pattern finalKeys, Errors errors) {
        this.client = client;
        this.presigner = presigner;
        this.properties = properties;
        this.temporaryKeys = temporaryKeys;
        this.finalKeys = finalKeys;
        this.errors = errors;
    }

    public Upload signUpload(String key, String contentType, long size) {
        validate(key, temporaryKeys);
        var request = PutObjectRequest.builder().bucket(properties.bucket()).key(key).contentType(contentType)
            .contentLength(size).ifNoneMatch("*").build();
        try {
            var signed = presigner.presignPutObject(PutObjectPresignRequest.builder()
                .signatureDuration(Duration.ofMinutes(5)).putObjectRequest(request).build());
            var headers = new HashMap<String, String>();
            signed.signedHeaders().forEach((name, values) -> {
                if (!name.equalsIgnoreCase("host")) {
                    headers.put(name, String.join(",", values));
                }
            });
            return new Upload(signed.url().toString(), signed.expiration(), headers);
        } catch (SdkException error) {
            throw new BusinessException(errors.unavailable());
        }
    }

    public byte[] read(String key, String contentType, long expectedSize) {
        validate(key, temporaryKeys);
        try (var stream = client.getObject(GetObjectRequest.builder().bucket(properties.bucket()).key(key)
                .overrideConfiguration(TIMEOUT).build())) {
            if (!Long.valueOf(expectedSize).equals(stream.response().contentLength())
                    || !contentType.equals(stream.response().contentType())) {
                stream.abort();
                throw new BusinessException(errors.invalid());
            }
            byte[] bytes = stream.readNBytes((int) expectedSize + 1);
            if (bytes.length != expectedSize) {
                stream.abort();
                throw new BusinessException(errors.invalid());
            }
            return bytes;
        } catch (S3Exception error) {
            if (error.statusCode() == 404) {
                throw new BusinessException(errors.notUploaded());
            }
            throw new BusinessException(errors.unavailable());
        } catch (IOException | SdkException error) {
            throw new BusinessException(errors.unavailable());
        }
    }

    public String publishPng(String key, byte[] png) {
        validate(key, finalKeys);
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
                    throw new BusinessException(errors.invalid());
                }
            }
        } catch (SdkException error) {
            throw new BusinessException(errors.unavailable());
        }
        return digest;
    }

    public Download signDownload(String key, Duration ttl) {
        validate(key, finalKeys);
        try {
            var signed = presigner.presignGetObject(GetObjectPresignRequest.builder()
                .signatureDuration(ttl)
                .getObjectRequest(GetObjectRequest.builder().bucket(properties.bucket()).key(key).build())
                .build());
            return new Download(signed.url().toString(), signed.expiration());
        } catch (SdkException error) {
            throw new BusinessException(errors.unavailable());
        }
    }

    public void delete(String key) {
        validate(key, temporaryKeys, finalKeys);
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
        throw new BusinessException(errors.unavailable());
    }

    private void deleteVersion(String key, String versionId) {
        client.deleteObject(DeleteObjectRequest.builder().bucket(properties.bucket()).key(key)
            .versionId(versionId).overrideConfiguration(TIMEOUT).build());
    }

    private void validate(String key, Pattern... patterns) {
        if (properties.bucket() == null || properties.bucket().isBlank()) {
            throw new BusinessException(ErrorCode.S3_BUCKET_NOT_CONFIGURED);
        }
        if (key != null) {
            for (var pattern : patterns) {
                if (pattern.matcher(key).matches()) return;
            }
        }
        throw new BusinessException(errors.invalid());
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
