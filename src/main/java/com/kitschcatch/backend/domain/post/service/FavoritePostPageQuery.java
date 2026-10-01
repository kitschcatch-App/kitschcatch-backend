// 내 관심 상품 조회의 페이지 크기와 조회 범위를 제한한다.
package com.kitschcatch.backend.domain.post.service;

import com.kitschcatch.backend.global.exception.BusinessException;
import com.kitschcatch.backend.global.exception.ErrorCode;

public record FavoritePostPageQuery(int page, int size) {
    public FavoritePostPageQuery {
        if (page < 0 || page > 10000 || size < 1 || size > 100) {
            throw new BusinessException(ErrorCode.INVALID_INPUT_VALUE);
        }
    }
}
