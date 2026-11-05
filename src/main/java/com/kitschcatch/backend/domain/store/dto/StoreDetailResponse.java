// 매장 기본 정보와 소개·사진·편의시설을 상세 응답으로 반환한다.
package com.kitschcatch.backend.domain.store.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.kitschcatch.backend.domain.store.entity.Store;
import com.kitschcatch.backend.domain.store.entity.StoreAmenityCode;
import com.kitschcatch.backend.domain.store.entity.StoreAmenityStatus;
import com.kitschcatch.backend.domain.store.entity.StoreBusinessHours;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.Comparator;
import java.util.List;

public record StoreDetailResponse(Long id, String name, String address,
                                  double latitude, double longitude, String phone,
                                  List<BusinessHours> businessHours, boolean favorited,
                                  @Schema(nullable = true, description = "검수한 매장 소개") String description,
                                  @Schema(nullable = true, description = "첫 사진의 임시 GET URL") String thumbnailUrl,
                                  @Schema(description = "표시 순서대로 정렬한 매장 사진. 없으면 빈 배열") List<Image> images,
                                  @Schema(description = "편의시설 확인 결과. 미등록은 UNKNOWN") List<Amenity> amenities,
                                  StoreReviewSummary.Preview reviewSummary) {
    public static StoreDetailResponse from(Store store, boolean favorited,
                                          List<Image> images, List<Amenity> amenities,
                                          StoreReviewSummary.Preview reviewSummary) {
        return new StoreDetailResponse(store.getId(), store.getName(), store.getAddress(),
            store.getLatitude(), store.getLongitude(), store.getPhone(),
            store.getBusinessHours().stream()
                .sorted(Comparator.comparing(StoreBusinessHours::getDayOfWeek))
                .map(hours -> new BusinessHours(hours.getDayOfWeek(), hours.getOpenTime(),
                    hours.getCloseTime(), hours.isClosed())).toList(), favorited,
            store.getDescription(), images.isEmpty() ? null : images.getFirst().imageUrl(), images, amenities, reviewSummary);
    }

    public record Image(long id, @Schema(description = "만료되는 S3 GET 서명 URL") String imageUrl, int sortOrder,
                        @Schema(nullable = true) String caption, @Schema(nullable = true) String credit,
                        @Schema(nullable = true) String sourceUrl) {}

    public record Amenity(StoreAmenityCode code, String label, StoreAmenityStatus status,
                          @Schema(nullable = true) String note) {}

    public record BusinessHours(
        DayOfWeek dayOfWeek,
        @JsonFormat(pattern = "HH:mm") @Schema(type = "string", example = "11:00", nullable = true) LocalTime openTime,
        @JsonFormat(pattern = "HH:mm") @Schema(type = "string", example = "20:00", nullable = true) LocalTime closeTime,
        boolean closed
    ) {}
}
