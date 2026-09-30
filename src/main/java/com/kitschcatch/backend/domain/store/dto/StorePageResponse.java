// 전국 매장 목록과 페이지 집계를 반환한다.
package com.kitschcatch.backend.domain.store.dto;

import com.kitschcatch.backend.domain.store.entity.Store;
import java.util.List;
import org.springframework.data.domain.Page;

public record StorePageResponse(List<StoreSummaryResponse> content, int page, int size,
                                long totalElements, int totalPages) {
    public static StorePageResponse from(Page<Store> stores) {
        return new StorePageResponse(stores.getContent().stream().map(StoreSummaryResponse::from).toList(),
            stores.getNumber(), stores.getSize(), stores.getTotalElements(), stores.getTotalPages());
    }
}
