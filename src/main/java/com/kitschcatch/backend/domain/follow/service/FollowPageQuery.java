// 팔로우 목록의 페이지 범위와 크기를 제한한다.
package com.kitschcatch.backend.domain.follow.service;

import com.kitschcatch.backend.global.exception.BusinessException;
import com.kitschcatch.backend.global.exception.ErrorCode;

public record FollowPageQuery(int page, int size) {
    public FollowPageQuery {
        if (page < 0 || page > 10000 || size < 1 || size > 100) {
            throw new BusinessException(ErrorCode.INVALID_INPUT_VALUE);
        }
    }
}
