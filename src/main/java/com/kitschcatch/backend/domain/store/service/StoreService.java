// 등록된 매장의 지역별 목록과 상세 운영 정보를 조회한다.
package com.kitschcatch.backend.domain.store.service;

import com.kitschcatch.backend.domain.store.dto.StoreDetailResponse;
import com.kitschcatch.backend.domain.store.dto.StorePageResponse;
import com.kitschcatch.backend.domain.store.repository.StoreRepository;
import com.kitschcatch.backend.global.exception.BusinessException;
import com.kitschcatch.backend.global.exception.ErrorCode;
import java.util.Set;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class StoreService {
    private static final Set<String> REGIONS = Set.of("서울", "부산", "대구", "인천", "광주", "대전", "울산",
        "세종", "경기", "강원", "충북", "충남", "전북", "전남", "경북", "경남", "제주");
    private final StoreRepository storeRepository;

    public StoreService(StoreRepository storeRepository) {
        this.storeRepository = storeRepository;
    }

    public StorePageResponse list(String region, StorePageQuery query) {
        if (region != null && !REGIONS.contains(region)) {
            throw new BusinessException(ErrorCode.INVALID_INPUT_VALUE);
        }
        var pageable = PageRequest.of(query.page(), query.size(), Sort.by("id"));
        return StorePageResponse.from(region == null
            ? storeRepository.findAll(pageable) : storeRepository.findByRegion(region, pageable));
    }

    public StoreDetailResponse detail(long storeId) {
        if (storeId <= 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST);
        }
        return StoreDetailResponse.from(storeRepository.findById(storeId)
            .orElseThrow(() -> new BusinessException(ErrorCode.STORE_NOT_FOUND)));
    }
}
