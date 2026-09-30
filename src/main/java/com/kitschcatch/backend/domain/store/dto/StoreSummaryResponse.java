// 지도 마커와 목록에 필요한 매장 기본 정보를 반환한다.
package com.kitschcatch.backend.domain.store.dto;

import com.kitschcatch.backend.domain.store.entity.Store;

public record StoreSummaryResponse(Long id, String name, String address,
                                   double latitude, double longitude, String phone, boolean favorited) {
    public static StoreSummaryResponse from(Store store, boolean favorited) {
        return new StoreSummaryResponse(store.getId(), store.getName(), store.getAddress(),
            store.getLatitude(), store.getLongitude(), store.getPhone(), favorited);
    }
}
