// 등록된 매장의 지역별 목록과 상세 운영 정보를 조회한다.
package com.kitschcatch.backend.domain.store.service;

import com.kitschcatch.backend.domain.store.dto.StoreDetailResponse;
import com.kitschcatch.backend.domain.store.dto.StorePageResponse;
import com.kitschcatch.backend.domain.store.entity.StoreAmenity;
import com.kitschcatch.backend.domain.store.entity.StoreAmenityCode;
import com.kitschcatch.backend.domain.store.entity.StoreAmenityStatus;
import com.kitschcatch.backend.domain.store.repository.StoreAmenityRepository;
import com.kitschcatch.backend.domain.store.repository.StoreImageRepository;
import com.kitschcatch.backend.domain.store.repository.StoreRepository;
import com.kitschcatch.backend.global.exception.BusinessException;
import com.kitschcatch.backend.global.exception.ErrorCode;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.Set;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class StoreService {
    private static final Set<String> REGIONS = Set.of("서울", "부산", "대구", "인천", "광주", "대전", "울산",
        "세종", "경기", "강원", "충북", "충남", "전북", "전남", "경북", "경남", "제주");
    private final StoreRepository storeRepository;
    private final StoreFavoriteQueryService favorites;

    private final StoreImageRepository images;
    private final StoreAmenityRepository amenities;
    private final StoreImageStorage imageStorage;

    public StoreService(StoreRepository storeRepository, StoreFavoriteQueryService favorites,
                        StoreImageRepository images, StoreAmenityRepository amenities, StoreImageStorage imageStorage) {
        this.storeRepository = storeRepository;
        this.favorites = favorites;
        this.images = images;
        this.amenities = amenities;
        this.imageStorage = imageStorage;
    }

    public StorePageResponse list(long userId, String region, StorePageQuery query) {
        if (region != null && !REGIONS.contains(region)) {
            throw new BusinessException(ErrorCode.INVALID_INPUT_VALUE);
        }
        var pageable = PageRequest.of(query.page(), query.size(), Sort.by("id"));
        var stores = region == null ? storeRepository.findAll(pageable) : storeRepository.findByRegion(region, pageable);
        var ids = stores.getContent().stream().map(store -> store.getId()).toList();
        return StorePageResponse.from(stores, favorites.favoritedIds(userId, ids));
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public StoreDetailResponse detail(long userId, long storeId) {
        if (storeId <= 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST);
        }
        var store = storeRepository.findById(storeId)
            .orElseThrow(() -> new BusinessException(ErrorCode.STORE_NOT_FOUND));
        var photoRows = images.findByStoreIdOrderBySortOrder(storeId);
        var known = new EnumMap<StoreAmenityCode, StoreAmenity>(StoreAmenityCode.class);
        amenities.findByStoreId(storeId).forEach(amenity -> known.put(amenity.getCode(), amenity));
        var facilityResponses = Arrays.stream(StoreAmenityCode.values()).map(code -> {
            var amenity = known.get(code);
            return new StoreDetailResponse.Amenity(code, code.label(),
                amenity == null ? StoreAmenityStatus.UNKNOWN : amenity.getStatus(),
                amenity == null ? null : amenity.getNote());
        }).toList();
        boolean favorited = favorites.favoritedIds(userId, Set.of(storeId)).contains(storeId);
        var photoResponses = photoRows.stream().map(photo -> new StoreDetailResponse.Image(
            photo.getId(), imageStorage.imageUrl(storeId, photo.getObjectKey()), photo.getSortOrder(),
            photo.getCaption(), photo.getCredit(), photo.getSourceUrl())).toList();
        return StoreDetailResponse.from(store, favorited, photoResponses, facilityResponses);
    }
}
