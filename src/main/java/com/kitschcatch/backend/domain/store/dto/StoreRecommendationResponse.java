// 기준 매장과 검색 조건, 거리순 추천 매장 목록을 반환한다.
package com.kitschcatch.backend.domain.store.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

public record StoreRecommendationResponse(
    @Schema(description = "거리 계산의 기준 매장 ID") long sourceStoreId,
    @Schema(description = "적용한 검색 반경(km)") int radiusKm,
    @Schema(description = "요청한 최대 추천 개수") int limit,
    @Schema(description = "기준 매장과 가까운 순서의 추천 목록. 후보가 없으면 빈 배열") List<NearbyStoreResponse> stores
) {}
