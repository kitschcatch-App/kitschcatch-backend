// 사용자와 상품 행을 잠가 관심 상품 변경을 직렬화하고 본인 관계만 변경한다.
package com.kitschcatch.backend.domain.post.service;

import com.kitschcatch.backend.domain.post.dto.PostFavoriteResponse;
import com.kitschcatch.backend.domain.post.entity.PostFavorite;
import com.kitschcatch.backend.domain.post.repository.PostFavoriteRepository;
import com.kitschcatch.backend.domain.post.repository.PostRepository;
import com.kitschcatch.backend.domain.user.repository.UserRepository;
import com.kitschcatch.backend.global.exception.BusinessException;
import com.kitschcatch.backend.global.exception.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PostFavoriteService {
    private final UserRepository users;
    private final PostRepository posts;
    private final PostFavoriteRepository favorites;

    public PostFavoriteService(UserRepository users, PostRepository posts, PostFavoriteRepository favorites) {
        this.users = users;
        this.posts = posts;
        this.favorites = favorites;
    }

    @Transactional
    public PostFavoriteResponse register(long userId, long postId) {
        validatePostId(postId);
        var user = users.findActiveByIdForUpdate(userId)
            .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_AUTH_TOKEN));
        // 상품 삭제·상태 변경과 등록이 같은 상품 잠금 순서에서 확정되도록 한다.
        var post = posts.findByIdForUpdate(postId).filter(value -> value.getDeletedAt() == null)
            .orElseThrow(() -> new BusinessException(ErrorCode.POST_NOT_FOUND));
        if (!favorites.existsByUserIdAndPostId(userId, postId)) {
            favorites.saveAndFlush(new PostFavorite(user, post));
        }
        return new PostFavoriteResponse(postId, true, favorites.countByPostId(postId));
    }

    @Transactional
    public PostFavoriteResponse remove(long userId, long postId) {
        validatePostId(postId);
        users.findByIdForUpdate(userId)
            .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
        // 소프트 삭제 상품은 목록에서 숨기지만 남은 본인 관계는 해제할 수 있다.
        posts.findByIdForUpdate(postId).orElseThrow(() -> new BusinessException(ErrorCode.POST_NOT_FOUND));
        favorites.deleteFavorite(userId, postId);
        return new PostFavoriteResponse(postId, false, favorites.countByPostId(postId));
    }

    private void validatePostId(long postId) {
        if (postId <= 0) throw new BusinessException(ErrorCode.BAD_REQUEST);
    }
}
