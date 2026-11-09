// 전액 환불 요청 사유와 금액 및 선택 증빙 사진 ID 목록을 검증한다.
package com.kitschcatch.backend.domain.order.dto;

import com.kitschcatch.backend.global.exception.BusinessException;
import com.kitschcatch.backend.global.exception.ErrorCode;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
import java.util.HashSet;
import java.util.List;
import tools.jackson.databind.annotation.JsonDeserialize;

public record RefundRequest(
    @NotBlank @Size(max = 200) String reason,
    @NotNull @Positive Long amount,
    @Schema(description = "검증 완료된 반품 사진 ID 목록. 최대 5장, 생략 가능.", example = "[501, 502]")
    @JsonDeserialize(contentUsing = RefundImageDtos.IntegerValue.class)
    @Size(max = RefundImageDtos.MAX_IMAGES) List<@NotNull @Positive Long> imageIds
) {
    public List<Long> validatedImageIds() {
        if (imageIds == null || imageIds.isEmpty()) return List.of();
        if (new HashSet<>(imageIds).size() != imageIds.size()) {
            throw new BusinessException(ErrorCode.REFUND_IMAGE_INVALID);
        }
        return imageIds;
    }
}
