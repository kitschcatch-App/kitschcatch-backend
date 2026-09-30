// 내 관심 매장의 페이지와 위치 정보를 반환하고 없는 거리·이미지는 null로 명시한다.
package com.kitschcatch.backend.domain.store.dto;

import com.kitschcatch.backend.domain.store.entity.StoreFavorite;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import org.springframework.data.domain.Page;

public record FavoriteStorePageResponse(List<FavoriteStore> content, int page, int size,
                                        long totalElements, int totalPages) {
    public static FavoriteStorePageResponse from(Page<StoreFavorite> favorites) {
        return new FavoriteStorePageResponse(favorites.getContent().stream().map(favorite -> {
            var store = favorite.getStore();
            return new FavoriteStore(store.getId(), store.getName(), store.getAddress(),
                store.getLatitude(), store.getLongitude(), store.getPhone(), true, null, null);
        }).toList(), favorites.getNumber(), favorites.getSize(), favorites.getTotalElements(), favorites.getTotalPages());
    }

    public record FavoriteStore(Long id, String name, String address, double latitude, double longitude,
        String phone, boolean favorited,
        @Schema(description = "현재 위치 입력이 없으므로 null. 거리 조회는 주변 매장 API 사용", nullable = true) Long distanceMeters,
        @Schema(description = "매장 이미지 데이터가 아직 없어 null", nullable = true) String thumbnailUrl) {}
}
