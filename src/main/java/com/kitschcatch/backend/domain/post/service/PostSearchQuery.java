// 상품 목록의 필터·정렬·페이지 계약을 검증한다.
package com.kitschcatch.backend.domain.post.service;

import com.kitschcatch.backend.domain.post.entity.ProductCategory;
import com.kitschcatch.backend.domain.post.entity.ProductCondition;
import com.kitschcatch.backend.domain.post.entity.ProductStatus;
import com.kitschcatch.backend.global.exception.BusinessException;
import com.kitschcatch.backend.global.exception.ErrorCode;
import org.springframework.data.domain.PageRequest;

public record PostSearchQuery(ProductStatus status, String keyword, ProductCategory category,
    ProductCondition condition, Long minPrice, Long maxPrice, PostSearchSort sort, int page, int size) {
    public PostSearchQuery {
        keyword = keyword == null ? null : keyword.strip();
        if (keyword != null && keyword.isEmpty()) keyword = null;
        if (sort == null) sort = PostSearchSort.LATEST;
        if (page < 0 || page > 10000 || size < 1 || size > 100
            || (keyword != null && keyword.length() > 1000)
            || (minPrice != null && minPrice < 0) || (maxPrice != null && maxPrice < 0)
            || (minPrice != null && maxPrice != null && minPrice > maxPrice)) {
            throw new BusinessException(ErrorCode.INVALID_INPUT_VALUE);
        }
    }

    public static PostSearchQuery from(String status, String keyword, String category, String condition,
        Long minPrice, Long maxPrice, String sort, int page, int size) {
        try {
            return new PostSearchQuery(status == null ? null : ProductStatus.valueOf(status), keyword,
                category == null ? null : ProductCategory.from(category),
                condition == null ? null : ProductCondition.valueOf(condition), minPrice, maxPrice,
                sort == null ? PostSearchSort.LATEST : PostSearchSort.valueOf(sort), page, size);
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(ErrorCode.INVALID_INPUT_VALUE);
        }
    }

    public PageRequest pageable() { return PageRequest.of(page, size); }
}
