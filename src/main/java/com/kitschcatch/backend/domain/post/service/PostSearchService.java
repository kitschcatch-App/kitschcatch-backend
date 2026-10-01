// 동일 읽기 스냅샷에서 상품 검색 페이지와 기존 상품 응답을 생성한다.
package com.kitschcatch.backend.domain.post.service;

import com.kitschcatch.backend.domain.post.dto.PostImageResponse;
import com.kitschcatch.backend.domain.post.dto.PostResponse;
import com.kitschcatch.backend.domain.post.entity.Post;
import com.kitschcatch.backend.domain.post.repository.PostSearchRepository;
import com.kitschcatch.backend.domain.user.repository.UserRepository;
import com.kitschcatch.backend.global.exception.BusinessException;
import com.kitschcatch.backend.global.exception.ErrorCode;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PostSearchService {
    private final PostSearchRepository repository;
    private final PostImageStorage images;
    private final UserRepository users;

    public PostSearchService(PostSearchRepository repository, PostImageStorage images, UserRepository users) {
        this.repository = repository;
        this.images = images;
        this.users = users;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Page<PostResponse> search(Long userId, PostSearchQuery query) {
        if (!users.existsById(userId)) throw new BusinessException(ErrorCode.INVALID_AUTH_TOKEN);
        return repository.search(query).map(this::response);
    }

    private PostResponse response(Post post) {
        var imageResponses = post.getImages().stream().map(image -> new PostImageResponse(
            image.getObjectKey(), images.imageUrl(image.getObjectKey()), image.getSortOrder())).toList();
        return new PostResponse(post.getId(), post.getUser().getId(), post.getUser().getNickname(),
            post.getTitle(), post.getDescription(), post.getPrice(), post.getProductCategory(),
            post.getProductCondition(), post.getProductStatus(), imageResponses, post.getCreatedAt(), post.getUpdatedAt());
    }
}
