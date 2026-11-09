// 신고에 저장된 채팅 이미지 참조를 검증하고 설정된 버킷에서만 조회한다.
package com.kitschcatch.backend.domain.report.review;

import static com.kitschcatch.backend.global.exception.ErrorCode.REPORT_EVIDENCE_NOT_FOUND;
import static com.kitschcatch.backend.global.exception.ErrorCode.REPORT_EVIDENCE_UNAVAILABLE;

import com.kitschcatch.backend.domain.post.storage.S3Properties;
import com.kitschcatch.backend.global.exception.BusinessException;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;

@Component
@RequiredArgsConstructor
public class ReportEvidenceImages {
    private static final int MAX_BYTES = 10 * 1024 * 1024;
    private static final Set<String> MEDIA_TYPES = Set.of("image/jpeg", "image/png", "image/gif", "image/webp");
    private static final Pattern FILE_NAME = Pattern.compile(
        "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.(jpg|jpeg|png|gif|webp)");

    private final S3Properties properties;
    private final S3Client s3;

    public Optional<String> capturePath(String url, long roomId, long senderId) {
        try {
            return key(URI.create(url).getPath(), roomId, senderId).map(value -> "/" + value);
        } catch (IllegalArgumentException invalid) {
            return Optional.empty();
        }
    }

    Optional<String> key(String path, long roomId, long senderId) {
        if (path == null) return Optional.empty();
        String prefix = properties.chatImagePrefix() + "/" + roomId + "/" + senderId + "/";
        String candidate = path;
        if (!properties.publicBaseUrl().isEmpty() && !path.startsWith("/" + prefix)) {
            String basePath = URI.create(properties.publicBaseUrl()).getPath();
            if (basePath != null && !basePath.isEmpty() && path.startsWith(basePath + "/")) {
                candidate = path.substring(basePath.length());
            }
        }
        if (!candidate.startsWith("/" + prefix)
            || !FILE_NAME.matcher(candidate.substring(prefix.length() + 1)).matches()) {
            return Optional.empty();
        }
        return Optional.of(candidate.substring(1));
    }

    Image load(String objectKey) {
        if (properties.bucket() == null || properties.bucket().isBlank()) {
            throw new BusinessException(REPORT_EVIDENCE_UNAVAILABLE);
        }
        var request = GetObjectRequest.builder().bucket(properties.bucket()).key(objectKey)
            .overrideConfiguration(config -> config.apiCallTimeout(Duration.ofSeconds(8))
                .apiCallAttemptTimeout(Duration.ofSeconds(4))).build();
        try {
            // SDK 호출이 끝나기 전에 본문을 읽어 응답 전체에 제한 시간을 적용한다.
            return s3.getObject(request, this::readImage)
                .orElseThrow(() -> new BusinessException(REPORT_EVIDENCE_NOT_FOUND));
        } catch (S3Exception error) {
            throw new BusinessException(error.statusCode() == 404 ? REPORT_EVIDENCE_NOT_FOUND : REPORT_EVIDENCE_UNAVAILABLE);
        } catch (SdkException error) {
            throw new BusinessException(REPORT_EVIDENCE_UNAVAILABLE);
        }
    }

    private Optional<Image> readImage(GetObjectResponse response, AbortableInputStream stream) throws IOException {
        String contentType = response.contentType();
        if (contentType == null || !MEDIA_TYPES.contains(contentType)
            || (response.contentLength() != null && response.contentLength() > MAX_BYTES)) {
            stream.abort();
            return Optional.empty();
        }
        byte[] bytes;
        try {
            bytes = stream.readNBytes(MAX_BYTES + 1);
        } catch (IOException error) {
            stream.abort();
            throw error;
        }
        if (bytes.length == 0 || bytes.length > MAX_BYTES) {
            stream.abort();
            return Optional.empty();
        }
        return Optional.of(new Image(bytes, MediaType.parseMediaType(contentType)));
    }

    record Image(byte[] bytes, MediaType contentType) {}
}
