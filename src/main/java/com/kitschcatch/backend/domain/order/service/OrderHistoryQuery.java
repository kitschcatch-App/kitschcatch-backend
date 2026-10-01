// 거래 목록의 주문 상태와 페이지 입력을 제한한다.
package com.kitschcatch.backend.domain.order.service;

import com.kitschcatch.backend.domain.order.entity.OrderStatus;
import com.kitschcatch.backend.global.exception.BusinessException;
import com.kitschcatch.backend.global.exception.ErrorCode;
import org.springframework.data.domain.PageRequest;

public record OrderHistoryQuery(OrderStatus status, int page, int size) {
    public OrderHistoryQuery {
        if (page < 0 || page > 10000 || size < 1 || size > 100) {
            throw new BusinessException(ErrorCode.INVALID_INPUT_VALUE);
        }
    }

    public static OrderHistoryQuery from(String status, int page, int size) {
        try {
            return new OrderHistoryQuery(status == null ? null : OrderStatus.valueOf(status), page, size);
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(ErrorCode.INVALID_INPUT_VALUE);
        }
    }

    public PageRequest pageable() { return PageRequest.of(page, size); }
}
