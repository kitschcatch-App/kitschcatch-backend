// 매장 위치·연락처와 월요일부터 정렬한 요일별 영업시간을 반환한다.
package com.kitschcatch.backend.domain.store.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.kitschcatch.backend.domain.store.entity.Store;
import com.kitschcatch.backend.domain.store.entity.StoreBusinessHours;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.Comparator;
import java.util.List;

public record StoreDetailResponse(Long id, String name, String address,
                                  double latitude, double longitude, String phone,
                                  List<BusinessHours> businessHours, boolean favorited) {
    public static StoreDetailResponse from(Store store, boolean favorited) {
        return new StoreDetailResponse(store.getId(), store.getName(), store.getAddress(),
            store.getLatitude(), store.getLongitude(), store.getPhone(),
            store.getBusinessHours().stream()
                .sorted(Comparator.comparing(StoreBusinessHours::getDayOfWeek))
                .map(hours -> new BusinessHours(hours.getDayOfWeek(), hours.getOpenTime(),
                    hours.getCloseTime(), hours.isClosed())).toList(), favorited);
    }

    public record BusinessHours(
        DayOfWeek dayOfWeek,
        @JsonFormat(pattern = "HH:mm") @Schema(type = "string", example = "11:00", nullable = true) LocalTime openTime,
        @JsonFormat(pattern = "HH:mm") @Schema(type = "string", example = "20:00", nullable = true) LocalTime closeTime,
        boolean closed
    ) {}
}
