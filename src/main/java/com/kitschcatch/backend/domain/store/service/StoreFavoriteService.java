// 같은 사용자의 변경을 직렬화하여 관심 매장 등록과 해제를 반복해도 안전하게 처리한다.
package com.kitschcatch.backend.domain.store.service;

import com.kitschcatch.backend.domain.store.dto.StoreFavoriteResponse;
import com.kitschcatch.backend.domain.store.entity.StoreFavorite;
import com.kitschcatch.backend.domain.store.repository.StoreFavoriteRepository;
import com.kitschcatch.backend.domain.store.repository.StoreRepository;
import com.kitschcatch.backend.domain.user.repository.UserRepository;
import com.kitschcatch.backend.global.exception.BusinessException;
import com.kitschcatch.backend.global.exception.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class StoreFavoriteService {
    private final UserRepository users;
    private final StoreRepository stores;
    private final StoreFavoriteRepository favorites;

    public StoreFavoriteService(UserRepository users, StoreRepository stores, StoreFavoriteRepository favorites) {
        this.users = users;
        this.stores = stores;
        this.favorites = favorites;
    }

    @Transactional
    public StoreFavoriteResponse register(long userId, long storeId) {
        validateStoreId(storeId);
        // 존재하지 않는 관계 행 대신 항상 존재하는 사용자 행을 먼저 잠근다.
        var user = users.findActiveByIdForUpdate(userId)
            .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_AUTH_TOKEN));
        var store = stores.findById(storeId)
            .orElseThrow(() -> new BusinessException(ErrorCode.STORE_NOT_FOUND));
        if (!favorites.existsByUserIdAndStoreId(userId, storeId)) {
            favorites.saveAndFlush(new StoreFavorite(user, store));
        }
        return new StoreFavoriteResponse(storeId, true, favorites.countByStoreId(storeId));
    }

    @Transactional
    public StoreFavoriteResponse remove(long userId, long storeId) {
        validateStoreId(storeId);
        users.findByIdForUpdate(userId)
            .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
        if (!stores.existsById(storeId)) throw new BusinessException(ErrorCode.STORE_NOT_FOUND);
        favorites.deleteFavorite(userId, storeId);
        return new StoreFavoriteResponse(storeId, false, favorites.countByStoreId(storeId));
    }

    private void validateStoreId(long storeId) {
        if (storeId <= 0) throw new BusinessException(ErrorCode.BAD_REQUEST);
    }
}
