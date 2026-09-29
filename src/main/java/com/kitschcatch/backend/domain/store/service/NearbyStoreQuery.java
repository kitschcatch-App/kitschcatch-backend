// 주변 검색의 좌표·반경을 검증하고 안전한 위도 후보 범위를 계산한다.
package com.kitschcatch.backend.domain.store.service;

import com.kitschcatch.backend.global.exception.BusinessException;
import com.kitschcatch.backend.global.exception.ErrorCode;

public record NearbyStoreQuery(double latitude, double longitude, int radiusKm, StorePageQuery page) {
    public static final double EARTH_RADIUS_METERS = 6371008.8;
    // 반경 경계의 부동소수점 연산 오차만 흡수한다.
    public static final double DISTANCE_EPSILON_METERS = 0.000001;

    public NearbyStoreQuery {
        if (!Double.isFinite(latitude) || latitude < -90 || latitude > 90
            || !Double.isFinite(longitude) || longitude < -180 || longitude > 180
            || (radiusKm != 1 && radiusKm != 3 && radiusKm != 5)) {
            throw new BusinessException(ErrorCode.STORE_QUERY_INVALID);
        }
    }

    public double radiusMeters() {
        return radiusKm * 1000.0;
    }

    public double minLatitude() {
        return Math.max(-90, latitude - Math.toDegrees((radiusMeters() + DISTANCE_EPSILON_METERS) / EARTH_RADIUS_METERS));
    }

    public double maxLatitude() {
        return Math.min(90, latitude + Math.toDegrees((radiusMeters() + DISTANCE_EPSILON_METERS) / EARTH_RADIUS_METERS));
    }
}
