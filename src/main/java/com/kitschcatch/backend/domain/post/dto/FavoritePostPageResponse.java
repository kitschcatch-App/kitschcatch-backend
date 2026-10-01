// 내 관심 상품의 현재 판매 상태와 대표 이미지 및 페이지 정보를 반환한다.
package com.kitschcatch.backend.domain.post.dto;

import com.kitschcatch.backend.domain.post.entity.ProductCategory;
import com.kitschcatch.backend.domain.post.entity.ProductCondition;
import com.kitschcatch.backend.domain.post.entity.ProductStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.util.List;

public record FavoritePostPageResponse(List<FavoritePost> content, int page, int size,
                                        long totalElements, int totalPages) {
    public record FavoritePost(Long id, Long sellerId, String sellerNickname, String title, Long price,
        ProductCategory productCategory, ProductCondition productCondition, ProductStatus productStatus,
        boolean favorited, LocalDateTime favoritedAt,
        @Schema(description = "정렬상 첫 이미지 URL. 이미지가 없으면 null", nullable = true) String thumbnailUrl) {}
}
