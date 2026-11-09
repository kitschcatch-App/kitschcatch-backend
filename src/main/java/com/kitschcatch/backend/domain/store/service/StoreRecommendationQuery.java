// 매장 추천 요청의 반경과 최대 개수를 검증한다.
package com.kitschcatch.backend.domain.store.service;

import com.kitschcatch.backend.global.exception.BusinessException;
import com.kitschcatch.backend.global.exception.ErrorCode;

public record StoreRecommendationQuery(int radiusKm, int limit) {
    public StoreRecommendationQuery {
        NearbyStoreQuery.validateRadius(radiusKm);
        if (limit < 1 || limit > 10) {
            throw new BusinessException(ErrorCode.INVALID_INPUT_VALUE);
        }
    }
}
