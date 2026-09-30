// 내 관심 매장을 최신 등록 순으로 조회하고 페이지 내 관심 매장 ID를 일괄 조회한다.
package com.kitschcatch.backend.domain.store.service;

import com.kitschcatch.backend.domain.store.dto.FavoriteStorePageResponse;
import com.kitschcatch.backend.domain.store.repository.StoreFavoriteRepository;
import com.kitschcatch.backend.domain.user.repository.UserRepository;
import com.kitschcatch.backend.global.exception.BusinessException;
import com.kitschcatch.backend.global.exception.ErrorCode;
import java.util.Collection;
import java.util.Set;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class StoreFavoriteQueryService {
    private final StoreFavoriteRepository favorites;
    private final UserRepository users;

    public StoreFavoriteQueryService(StoreFavoriteRepository favorites, UserRepository users) {
        this.favorites = favorites;
        this.users = users;
    }

    public FavoriteStorePageResponse list(long userId, StorePageQuery query) {
        if (!users.existsById(userId)) throw new BusinessException(ErrorCode.USER_NOT_FOUND);
        return FavoriteStorePageResponse.from(favorites.findPageByUserId(userId,
            PageRequest.of(query.page(), query.size(), Sort.by(Sort.Direction.DESC, "createdAt", "id"))));
    }

    public Set<Long> favoritedIds(long userId, Collection<Long> storeIds) {
        return storeIds.isEmpty() ? Set.of() : favorites.findFavoritedStoreIds(userId, storeIds);
    }
}
