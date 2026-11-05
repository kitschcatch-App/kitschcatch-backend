// 매장 리뷰의 공개 조회, 별점 집계와 작성 제한 조회를 제공한다.
package com.kitschcatch.backend.domain.store.repository;

import com.kitschcatch.backend.domain.store.entity.StoreReview;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface StoreReviewRepository extends JpaRepository<StoreReview, Long> {
    String PUBLIC = """
        r.deletedAt is null and r.hiddenAt is null and a.withdrawnAt is null
        """;

    @Query(value = """
        select r from StoreReview r join fetch r.author a
        where r.store.id = :storeId and
        """ + PUBLIC + " order by r.createdAt desc, r.id desc",
        countQuery = """
        select count(r) from StoreReview r join r.author a
        where r.store.id = :storeId and
        """ + PUBLIC)
    Page<StoreReview> findPublicPage(long storeId, Pageable pageable);

    @Query("select r from StoreReview r join fetch r.author a where r.id = :id and " + PUBLIC)
    Optional<StoreReview> findPublicById(long id);

    Optional<StoreReview> findByStoreIdAndAuthorIdAndDeletedAtIsNull(long storeId, long authorId);

    @Query("""
        select count(r) > 0 from StoreReview r
        where r.store.id = :storeId and r.author.id = :authorId
          and (r.deletedAt is null or r.hiddenAt is not null)
        """)
    boolean existsBlockingCreation(long storeId, long authorId);

    long countByAuthorIdAndStoreIdAndCreatedAtGreaterThanEqual(long authorId, long storeId, Instant cutoff);

    @Query("""
        select r.rating as rating, count(r) as reviewCount
        from StoreReview r join r.author a
        where r.store.id = :storeId and
        """ + PUBLIC + " group by r.rating")
    List<RatingCount> countPublicByRating(long storeId);

    interface RatingCount {
        int getRating();
        long getReviewCount();
    }
}
