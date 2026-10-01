// 관심 상품의 중복 확인·삭제·집계와 삭제되지 않은 사용자별 페이지를 조회한다.
package com.kitschcatch.backend.domain.post.repository;

import com.kitschcatch.backend.domain.post.entity.PostFavorite;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PostFavoriteRepository extends JpaRepository<PostFavorite, Long> {
    boolean existsByUserIdAndPostId(Long userId, Long postId);

    long countByPostId(Long postId);

    @Modifying(flushAutomatically = true)
    @Query("delete from PostFavorite f where f.user.id = :userId and f.post.id = :postId")
    int deleteFavorite(@Param("userId") Long userId, @Param("postId") Long postId);

    @Query(value = "select f from PostFavorite f join fetch f.post p join fetch p.user where f.user.id = :userId and p.deletedAt is null",
        countQuery = "select count(f) from PostFavorite f where f.user.id = :userId and f.post.deletedAt is null")
    Page<PostFavorite> findPageByUserId(@Param("userId") Long userId, Pageable pageable);
}
