// 관심 상품 페이지의 대표 이미지 키를 한 번의 조회로 가져온다.
package com.kitschcatch.backend.domain.post.repository;

import com.kitschcatch.backend.domain.post.entity.PostImage;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

public interface PostFavoriteImageRepository extends Repository<PostImage, Long> {
    @Query("""
        select i.post.id as postId, i.objectKey as objectKey from PostImage i
        where i.post.id in :postIds and not exists (
            select earlier.id from PostImage earlier where earlier.post.id = i.post.id
            and (earlier.sortOrder < i.sortOrder or (earlier.sortOrder = i.sortOrder and earlier.id < i.id)))
        """)
    List<Thumbnail> findThumbnails(@Param("postIds") Collection<Long> postIds);

    interface Thumbnail {
        Long getPostId();
        String getObjectKey();
    }
}
