// 반품 사진 업로드, 완료와 비공개 조회의 API 형식을 정의한다.
package com.kitschcatch.backend.domain.order.dto;

import com.kitschcatch.backend.domain.order.entity.RefundImage.State;
import com.kitschcatch.backend.global.image.ImageNormalizer;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class RefundImageDtos {
    public static final int MAX_IMAGES = 5;

    private RefundImageDtos() {}

    public record File(
        @Schema(example = "damage.jpg") @NotBlank @Size(max = 200) String fileName,
        @Schema(example = "image/jpeg") @NotBlank @Size(max = 30) String contentType,
        @Schema(description = "원본 파일 크기(바이트)", example = "1250000")
        @Min(1) @Max(ImageNormalizer.MAX_BYTES) long fileSize
    ) {}

    public record UploadRequest(@NotEmpty @Size(max = MAX_IMAGES) List<@NotNull @Valid File> images) {}
    public record Upload(long imageId, String uploadUrl, Instant expiresAt, Map<String, String> headers) {}
    public record Uploads(List<Upload> images) {}
    public record Completed(long imageId, State state, int width, int height) {}
    public record Image(long imageId, String imageUrl, Instant expiresAt, int sortOrder) {}
}
