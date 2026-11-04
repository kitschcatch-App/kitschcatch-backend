// 동일 읽기 스냅샷에서 상품 검색 페이지와 기존 상품 응답을 생성한다.
package com.kitschcatch.backend.domain.post.service;

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
    private final PostResponseMapper mapper;
    private final PostCountQueryService counts;
    private final UserRepository users;

    public PostSearchService(PostSearchRepository repository, PostImageStorage images, UserRepository users, PostCountQueryService counts) {
        this.repository = repository;
        this.mapper = new PostResponseMapper(images);
        this.counts = counts;
        this.users = users;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Page<PostResponse> search(Long userId, PostSearchQuery query) {
        if (!users.existsById(userId)) throw new BusinessException(ErrorCode.INVALID_AUTH_TOKEN);
        var page = repository.search(query);
        var totals = counts.findByPostIds(page.getContent().stream().map(Post::getId).toList());
        return page.map(post -> mapper.from(post, totals.get(post.getId())));
    }
}
