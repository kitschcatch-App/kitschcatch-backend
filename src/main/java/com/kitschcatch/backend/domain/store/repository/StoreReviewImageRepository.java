// 사진 연결 조회와 작업별 행 잠금 및 정리 후보를 제공한다.
package com.kitschcatch.backend.domain.store.repository;

import com.kitschcatch.backend.domain.store.entity.StoreReviewImage;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;

public interface StoreReviewImageRepository extends JpaRepository<StoreReviewImage, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from StoreReviewImage i where i.id = :id")
    Optional<StoreReviewImage> lockById(long id);

    List<StoreReviewImage> findByReviewIdOrderBySortOrder(long reviewId);

    @Query("select i from StoreReviewImage i where i.review.id in :ids order by i.review.id, i.sortOrder")
    List<StoreReviewImage> findByReviewIds(Collection<Long> ids);

    long countByOwnerIdAndCreatedAtGreaterThanEqual(long ownerId, Instant cutoff);

    @Query(value = """
        select i from StoreReviewImage i join fetch i.review r join fetch i.owner a
        where i.store.id = :storeId and i.state = 'ATTACHED'
          and r.deletedAt is null and r.hiddenAt is null and a.withdrawnAt is null
        order by r.createdAt desc, r.id desc, i.sortOrder
        """, countQuery = """
        select count(i) from StoreReviewImage i join i.review r join i.owner a
        where i.store.id = :storeId and i.state = 'ATTACHED'
          and r.deletedAt is null and r.hiddenAt is null and a.withdrawnAt is null
        """)
    Page<StoreReviewImage> gallery(long storeId, Pageable pageable);

    @Query("""
        select i.id from StoreReviewImage i left join i.review r join i.owner a
        where i.state <> 'DELETED' and i.cleanupAt <= :now
          and (i.state <> 'ATTACHED' or i.temporaryCleaned = false
               or (r.evidenceUntil is not null and r.evidenceUntil <= :now)
               or (r.evidenceUntil is null and (r.deletedAt is not null or a.withdrawnAt is not null)
                   and r.hiddenAt is null))
        order by i.cleanupAt, i.id
        """)
    List<Long> due(Instant now, Pageable pageable);
}
