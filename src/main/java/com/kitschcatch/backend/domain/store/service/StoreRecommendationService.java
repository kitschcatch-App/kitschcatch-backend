// 선택한 매장의 저장 좌표를 기준으로 가까운 다른 매장을 조회한다.
package com.kitschcatch.backend.domain.store.service;

import com.kitschcatch.backend.domain.store.dto.StoreRecommendationResponse;
import com.kitschcatch.backend.domain.store.repository.StoreRepository;
import com.kitschcatch.backend.global.exception.BusinessException;
import com.kitschcatch.backend.global.exception.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class StoreRecommendationService {
    private final StoreRepository stores;
    private final NearbyStoreService nearbyStores;

    public StoreRecommendationService(StoreRepository stores, NearbyStoreService nearbyStores) {
        this.stores = stores;
        this.nearbyStores = nearbyStores;
    }

    public StoreRecommendationResponse recommend(long userId, long storeId, StoreRecommendationQuery query) {
        if (storeId <= 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST);
        }
        var source = stores.findById(storeId)
            .orElseThrow(() -> new BusinessException(ErrorCode.STORE_NOT_FOUND));
        var nearby = new NearbyStoreQuery(source.getLatitude(), source.getLongitude(), query.radiusKm(),
            new StorePageQuery(0, query.limit()));
        return new StoreRecommendationResponse(storeId, query.radiusKm(), query.limit(),
            nearbyStores.nearby(userId, nearby, storeId).stores());
    }
}
