// 관심 매장 변경 결과와 해당 매장의 전체 관심 등록 수를 반환한다.
package com.kitschcatch.backend.domain.store.dto;

import io.swagger.v3.oas.annotations.media.Schema;

public record StoreFavoriteResponse(Long storeId, boolean favorited,
    @Schema(description = "변경 후 조회 시점의 전체 사용자 관심 등록 수") long favoriteCount) {}
