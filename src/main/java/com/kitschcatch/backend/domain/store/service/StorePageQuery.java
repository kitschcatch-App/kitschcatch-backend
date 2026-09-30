// 매장 조회의 페이지 크기와 조회 범위를 제한한다.
package com.kitschcatch.backend.domain.store.service;

import com.kitschcatch.backend.global.exception.BusinessException;
import com.kitschcatch.backend.global.exception.ErrorCode;

public record StorePageQuery(int page, int size) {
    public StorePageQuery {
        if (page < 0 || page > 10000 || size < 1 || size > 100) {
            throw new BusinessException(ErrorCode.INVALID_INPUT_VALUE);
        }
    }

    public long offset() {
        return (long) page * size;
    }
}
