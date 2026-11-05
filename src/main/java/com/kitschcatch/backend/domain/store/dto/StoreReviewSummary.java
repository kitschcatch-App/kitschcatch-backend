// 매장 평점 분포와 상세 화면에서 사용하는 요약을 반환한다.
package com.kitschcatch.backend.domain.store.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.util.Map;

public record StoreReviewSummary(long reviewCount,
                                 @JsonInclude(JsonInclude.Include.ALWAYS)
                                 @Schema(nullable = true, description = "소수 둘째 자리 평균. 리뷰가 없으면 null")
                                 BigDecimal averageRating,
                                 Map<Integer, Long> ratingCounts, long visibleReviewCount) {
    public Preview preview() {
        return new Preview(reviewCount, averageRating);
    }

    public record Preview(long reviewCount,
                          @JsonInclude(JsonInclude.Include.ALWAYS)
                          @Schema(nullable = true) BigDecimal averageRating) {}
}
