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
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.annotation.JsonDeserialize;

public final class RefundImageDtos {
    public static final int MAX_IMAGES = 5;

    private RefundImageDtos() {}

    public record File(
        @Schema(example = "damage.jpg") @NotBlank @Size(max = 200) String fileName,
        @Schema(example = "image/jpeg") @NotBlank @Size(max = 30) String contentType,
        @Schema(description = "원본 파일 크기(바이트)", example = "1250000")
        @JsonDeserialize(using = IntegerValue.class) @Min(1) @Max(ImageNormalizer.MAX_BYTES) long fileSize
    ) {}

    public record UploadRequest(@NotEmpty @Size(max = MAX_IMAGES) List<@NotNull @Valid File> images) {}
    public record Upload(long imageId, String uploadUrl, Instant expiresAt, Map<String, String> headers) {}
    public record Uploads(List<Upload> images) {}
    public record Completed(long imageId, State state, int width, int height) {}
    public record Image(long imageId,
                        @Schema(nullable = true, description = "발급에 실패하면 null. 환불 조회를 다시 호출해 갱신합니다.")
                        String imageUrl,
                        @Schema(nullable = true, description = "조회 URL이 없으면 null.") Instant expiresAt,
                        int sortOrder) {}

    public static final class IntegerValue extends ValueDeserializer<Long> {
        @Override
        public Long deserialize(JsonParser parser, DeserializationContext context) {
            if (!parser.hasToken(JsonToken.VALUE_NUMBER_INT)) {
                return context.reportInputMismatch(Long.class, "사진 ID와 파일 크기는 JSON 정수여야 합니다.");
            }
            return parser.getLongValue();
        }
    }
}
