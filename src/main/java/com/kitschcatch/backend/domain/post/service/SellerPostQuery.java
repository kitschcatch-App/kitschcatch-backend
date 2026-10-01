// 판매자 상품 목록의 상태와 페이지 입력을 검증한다.
package com.kitschcatch.backend.domain.post.service;

import com.kitschcatch.backend.domain.post.entity.ProductStatus;
import com.kitschcatch.backend.global.exception.BusinessException;
import com.kitschcatch.backend.global.exception.ErrorCode;
import org.springframework.data.domain.PageRequest;

public record SellerPostQuery(ProductStatus status, int page, int size) {
    public SellerPostQuery {
        if (page < 0 || page > 10000 || size < 1 || size > 100) {
            throw new BusinessException(ErrorCode.INVALID_INPUT_VALUE);
        }
    }
    public static SellerPostQuery from(String status, int page, int size) {
        try {
            return new SellerPostQuery(status == null ? null : ProductStatus.valueOf(status), page, size);
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(ErrorCode.INVALID_INPUT_VALUE);
        }
    }
    public PageRequest pageable() { return PageRequest.of(page, size); }
}
