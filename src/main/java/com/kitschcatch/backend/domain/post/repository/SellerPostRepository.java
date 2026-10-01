// 판매자의 삭제되지 않은 상품을 상태별로 페이지 조회한다.
package com.kitschcatch.backend.domain.post.repository;

import com.kitschcatch.backend.domain.post.entity.Post;
import com.kitschcatch.backend.domain.post.entity.ProductStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

public interface SellerPostRepository extends Repository<Post, Long> {
    @EntityGraph(attributePaths = "user")
    @Query("""
        select p from Post p
        where p.user.id = :sellerId and p.deletedAt is null
          and (:status is null or p.productStatus = :status)
        order by p.createdAt desc, p.id desc
        """)
    Page<Post> findSellerPosts(@Param("sellerId") long sellerId,
        @Param("status") ProductStatus status, Pageable pageable);
}
