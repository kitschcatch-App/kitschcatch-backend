// 실제 구매 확정 건수와 받은 후기의 평점 집계를 반환한다.
package com.kitschcatch.backend.domain.review.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;

public record TrustInfoResponse(long userId, long completedTransactionCount, long reviewCount,
    @JsonInclude(JsonInclude.Include.ALWAYS) BigDecimal averageRating) {}
