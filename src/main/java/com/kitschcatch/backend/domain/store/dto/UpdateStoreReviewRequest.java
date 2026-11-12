// 조회 버전과 사용자가 변경할 매장 리뷰 필드를 받는다.
package com.kitschcatch.backend.domain.store.dto;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.List;

public record UpdateStoreReviewRequest(
    @NotNull @PositiveOrZero Long version,
    @DecimalMin("1") @DecimalMax("5") @Digits(integer = 1, fraction = 0) BigDecimal rating,
    String content,
    @Size(max = 10) List<@NotNull @Positive Long> imageIds
) {}
