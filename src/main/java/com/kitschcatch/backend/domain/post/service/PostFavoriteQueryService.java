// 삭제되지 않은 내 관심 상품을 최신 등록순으로 조회하고 대표 이미지를 일괄 조합한다.
package com.kitschcatch.backend.domain.post.service;

import com.kitschcatch.backend.domain.post.dto.FavoritePostPageResponse;
import com.kitschcatch.backend.domain.post.dto.FavoritePostPageResponse.FavoritePost;
import com.kitschcatch.backend.domain.post.repository.PostFavoriteImageRepository;
import com.kitschcatch.backend.domain.post.repository.PostFavoriteRepository;
import com.kitschcatch.backend.domain.user.repository.UserRepository;
import com.kitschcatch.backend.global.exception.BusinessException;
import com.kitschcatch.backend.global.exception.ErrorCode;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
public class PostFavoriteQueryService {
    private final PostFavoriteRepository favorites;
    private final PostFavoriteImageRepository images;
    private final PostImageStorage storage;
    private final UserRepository users;
    private final PostCountQueryService counts;

    public PostFavoriteQueryService(PostFavoriteRepository favorites, PostFavoriteImageRepository images,
        PostImageStorage storage, UserRepository users, PostCountQueryService counts) {
        this.favorites = favorites;
        this.images = images;
        this.storage = storage;
        this.users = users;
        this.counts = counts;
    }

    public FavoritePostPageResponse list(long userId, FavoritePostPageQuery query) {
        if (!users.existsById(userId)) throw new BusinessException(ErrorCode.USER_NOT_FOUND);
        var page = favorites.findPageByUserId(userId,
            PageRequest.of(query.page(), query.size(), Sort.by(Sort.Direction.DESC, "createdAt", "id")));
        var postIds = page.getContent().stream().map(favorite -> favorite.getPost().getId()).toList();
        var totals = counts.findByPostIds(postIds);
        Map<Long, String> thumbnails = postIds.isEmpty() ? Map.of() : images.findThumbnails(postIds).stream()
            .collect(Collectors.toMap(PostFavoriteImageRepository.Thumbnail::getPostId,
                image -> storage.imageUrl(image.getObjectKey())));
        var content = page.getContent().stream().map(favorite -> {
            var post = favorite.getPost();
            return new FavoritePost(post.getId(), post.getUser().getId(), post.getUser().getNickname(),
                post.getTitle(), post.getPrice(), post.getProductCategory(), post.getProductCondition(),
                post.getProductStatus(), true, favorite.getCreatedAt(), thumbnails.get(post.getId()), post.getCreatedAt(),
                totals.get(post.getId()).favoriteCount(), totals.get(post.getId()).chatRoomCount());
        }).toList();
        return new FavoritePostPageResponse(content, page.getNumber(), page.getSize(),
            page.getTotalElements(), page.getTotalPages());
    }
}
