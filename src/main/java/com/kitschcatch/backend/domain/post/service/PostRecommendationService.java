// 열람 순서대로 카테고리별 기준을 선택하고 추천 상품 응답을 만든다.
package com.kitschcatch.backend.domain.post.service;

import com.kitschcatch.backend.domain.post.dto.PostRecommendationRequest;
import com.kitschcatch.backend.domain.post.dto.PostRecommendationResponse;
import com.kitschcatch.backend.domain.post.entity.Post;
import com.kitschcatch.backend.domain.post.entity.ProductCategory;
import com.kitschcatch.backend.domain.post.repository.PostRecommendationRepository;
import com.kitschcatch.backend.domain.post.repository.PostRecommendationRepository.ReferencePost;
import com.kitschcatch.backend.domain.user.repository.UserRepository;
import com.kitschcatch.backend.global.exception.BusinessException;
import com.kitschcatch.backend.global.exception.ErrorCode;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PostRecommendationService {
    private final PostRecommendationRepository repository;
    private final UserRepository users;
    private final PostCountQueryService counts;
    private final PostResponseMapper mapper;

    public PostRecommendationService(PostRecommendationRepository repository, UserRepository users,
                                     PostCountQueryService counts, PostResponseMapper mapper) {
        this.repository = repository;
        this.users = users;
        this.counts = counts;
        this.mapper = mapper;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PostRecommendationResponse recommend(long viewerId, PostRecommendationRequest request) {
        if (!users.existsByIdAndWithdrawnAtIsNull(viewerId)) {
            throw new BusinessException(ErrorCode.INVALID_AUTH_TOKEN);
        }
        var recentIds = new LinkedHashSet<>(request.recentPostIds());
        if (recentIds.isEmpty()) return new PostRecommendationResponse(request.limit(), List.of());

        var references = repository.findReferences(viewerId, recentIds).stream()
            .collect(Collectors.toMap(ReferencePost::id, Function.identity()));
        var referencePrices = new LinkedHashMap<ProductCategory, Long>();
        for (Long id : recentIds) {
            var reference = references.get(id);
            if (reference != null) referencePrices.putIfAbsent(reference.category(), reference.price());
        }
        if (referencePrices.isEmpty()) return new PostRecommendationResponse(request.limit(), List.of());

        var posts = repository.findRecommendations(viewerId, recentIds, referencePrices, request.limit());
        var totals = counts.findByPostIds(posts.stream().map(Post::getId).toList());
        return new PostRecommendationResponse(request.limit(),
            posts.stream().map(post -> mapper.from(post, totals.get(post.getId()))).toList());
    }
}
