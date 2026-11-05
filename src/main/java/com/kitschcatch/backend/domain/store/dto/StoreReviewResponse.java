// 매장 리뷰와 공개 작성자 정보를 반환한다.
package com.kitschcatch.backend.domain.store.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;

public record StoreReviewResponse(long reviewId, long storeId, Author author, int rating, String content,
                                  Instant createdAt, Instant updatedAt, boolean mine, boolean hidden, long version,
                                  @Schema(description = "리뷰 사진 목록. 현재는 빈 배열") List<Image> images) {
    public record Author(long id, String nickname, @Schema(nullable = true) String profileImageUrl) {}

    public record Image(long id, String imageUrl, int sortOrder) {}

    public record Page(List<StoreReviewResponse> content, int page, int size, long totalElements, int totalPages) {}
}
