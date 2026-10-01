// 후기의 정수 평점과 비어 있지 않은 본문을 검증한다.
package com.kitschcatch.backend.domain.review.dto;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;

public record CreateReviewRequest(
    @NotNull @DecimalMin("1") @DecimalMax("5") @Digits(integer = 1, fraction = 0) BigDecimal rating,
    @NotBlank @Size(max = 1000) String content
) {}
