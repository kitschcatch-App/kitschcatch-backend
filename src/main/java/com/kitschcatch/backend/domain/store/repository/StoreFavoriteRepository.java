// 관심 매장의 중복 확인·삭제·집계와 사용자별 페이지 및 관심 여부를 조회한다.
package com.kitschcatch.backend.domain.store.repository;

import com.kitschcatch.backend.domain.store.entity.StoreFavorite;
import java.util.Collection;
import java.util.Set;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface StoreFavoriteRepository extends JpaRepository<StoreFavorite, Long> {
    boolean existsByUserIdAndStoreId(Long userId, Long storeId);

    long countByStoreId(Long storeId);

    @Modifying(flushAutomatically = true)
    @Query("delete from StoreFavorite f where f.user.id = :userId and f.store.id = :storeId")
    int deleteFavorite(@Param("userId") Long userId, @Param("storeId") Long storeId);

    @Query(value = "select f from StoreFavorite f join fetch f.store where f.user.id = :userId",
        countQuery = "select count(f) from StoreFavorite f where f.user.id = :userId")
    Page<StoreFavorite> findPageByUserId(@Param("userId") Long userId, Pageable pageable);

    @Query("select f.store.id from StoreFavorite f where f.user.id = :userId and f.store.id in :storeIds")
    Set<Long> findFavoritedStoreIds(@Param("userId") Long userId, @Param("storeIds") Collection<Long> storeIds);
}
