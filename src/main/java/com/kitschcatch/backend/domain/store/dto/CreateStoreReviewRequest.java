// 매장 리뷰 작성에 필요한 별점과 본문을 받는다.
package com.kitschcatch.backend.domain.store.dto;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.List;

public record CreateStoreReviewRequest(
    @NotNull @DecimalMin("1") @DecimalMax("5") @Digits(integer = 1, fraction = 0) BigDecimal rating,
    @NotBlank String content,
    @Size(max = 10) List<@NotNull @Positive Long> imageIds
) {}
