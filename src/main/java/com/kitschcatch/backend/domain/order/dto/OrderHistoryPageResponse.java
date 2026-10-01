// 거래 목록 내용과 요청 페이지 및 전체 건수를 반환한다.
package com.kitschcatch.backend.domain.order.dto;

import java.util.List;
import org.springframework.data.domain.Page;

public record OrderHistoryPageResponse<T>(List<T> content, int page, int size, long totalElements, int totalPages) {
    public static <T> OrderHistoryPageResponse<T> from(Page<T> page) {
        return new OrderHistoryPageResponse<>(page.getContent(), page.getNumber(), page.getSize(),
            page.getTotalElements(), page.getTotalPages());
    }
}
