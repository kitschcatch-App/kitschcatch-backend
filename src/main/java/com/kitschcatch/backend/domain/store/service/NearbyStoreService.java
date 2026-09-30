// 주변 매장 조회 결과에서 다음 페이지 여부를 구하고 응답 크기를 제한한다.
package com.kitschcatch.backend.domain.store.service;

import com.kitschcatch.backend.domain.store.dto.NearbyStorePageResponse;
import com.kitschcatch.backend.domain.store.repository.NearbyStoreRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class NearbyStoreService {
    private final NearbyStoreRepository repository;
    private final StoreFavoriteQueryService favorites;

    public NearbyStoreService(NearbyStoreRepository repository, StoreFavoriteQueryService favorites) {
        this.repository = repository;
        this.favorites = favorites;
    }

    public NearbyStorePageResponse nearby(long userId, NearbyStoreQuery query) {
        var stores = repository.findNearby(query);
        boolean hasNext = stores.size() > query.page().size();
        var page = stores.stream().limit(query.page().size()).toList();
        var ids = favorites.favoritedIds(userId, page.stream().map(store -> store.id()).toList());
        return new NearbyStorePageResponse(page.stream().map(store -> store.withFavorited(ids.contains(store.id()))).toList(),
            query.page().page(), query.page().size(), hasNext);
    }
}
