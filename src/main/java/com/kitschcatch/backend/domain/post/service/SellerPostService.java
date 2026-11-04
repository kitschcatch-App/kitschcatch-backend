// 인증 사용자와 조회 대상 판매자를 확인하고 상품 목록을 읽는다.
package com.kitschcatch.backend.domain.post.service;

import com.kitschcatch.backend.domain.post.dto.SellerPostPageResponse;
import com.kitschcatch.backend.domain.post.repository.SellerPostRepository;
import com.kitschcatch.backend.domain.user.repository.UserRepository;
import com.kitschcatch.backend.global.exception.BusinessException;
import com.kitschcatch.backend.global.exception.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
public class SellerPostService {
    private final SellerPostRepository posts;
    private final UserRepository users;
    private final PostResponseMapper mapper;
    private final PostCountQueryService counts;
    public SellerPostService(SellerPostRepository posts, UserRepository users, PostResponseMapper mapper, PostCountQueryService counts) {
        this.posts = posts; this.users = users; this.mapper = mapper; this.counts = counts;
    }
    public SellerPostPageResponse list(long currentUserId, long sellerId, SellerPostQuery query) {
        if (sellerId < 1) throw new BusinessException(ErrorCode.INVALID_INPUT_VALUE);
        if (!users.existsById(currentUserId)) throw new BusinessException(ErrorCode.INVALID_AUTH_TOKEN);
        if (sellerId != currentUserId && !users.existsById(sellerId)) {
            throw new BusinessException(ErrorCode.USER_NOT_FOUND);
        }
        var page = posts.findSellerPosts(sellerId, query.status(), query.pageable());
        var totals = counts.findByPostIds(page.getContent().stream().map(post -> post.getId()).toList());
        return SellerPostPageResponse.from(page.map(post -> mapper.from(post, totals.get(post.getId()))));
    }
}
