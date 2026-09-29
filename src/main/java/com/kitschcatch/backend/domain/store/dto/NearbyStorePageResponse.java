// 제한된 주변 매장 목록과 다음 페이지 존재 여부를 반환한다.
package com.kitschcatch.backend.domain.store.dto;

import java.util.List;

public record NearbyStorePageResponse(List<NearbyStoreResponse> stores, int page, int size, boolean hasNext) {}
