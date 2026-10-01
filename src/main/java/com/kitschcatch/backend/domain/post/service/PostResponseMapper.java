// 상품 조회와 판매자 목록에서 공통 상품 응답을 만든다.
package com.kitschcatch.backend.domain.post.service;

import com.kitschcatch.backend.domain.post.dto.PostImageResponse;
import com.kitschcatch.backend.domain.post.dto.PostResponse;
import com.kitschcatch.backend.domain.post.entity.Post;
import org.springframework.stereotype.Component;

@Component
public class PostResponseMapper {
    private final PostImageStorage images;
    public PostResponseMapper(PostImageStorage images) { this.images = images; }

    public PostResponse from(Post post) {
        return new PostResponse(post.getId(), post.getUser().getId(), post.getUser().getNickname(),
            post.getTitle(), post.getDescription(), post.getPrice(), post.getProductCategory(),
            post.getProductCondition(), post.getProductStatus(), post.getImages().stream()
                .map(image -> new PostImageResponse(image.getObjectKey(), images.imageUrl(image.getObjectKey()), image.getSortOrder()))
                .toList(), post.getCreatedAt(), post.getUpdatedAt());
    }
}
