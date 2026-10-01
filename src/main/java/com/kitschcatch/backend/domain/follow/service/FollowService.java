// 팔로우 변경을 사용자 ID 순서로 직렬화하고 방향별 공개 목록을 조회한다.
package com.kitschcatch.backend.domain.follow.service;

import com.kitschcatch.backend.domain.follow.dto.*;
import com.kitschcatch.backend.domain.follow.entity.UserFollow;
import com.kitschcatch.backend.domain.follow.repository.UserFollowRepository;
import com.kitschcatch.backend.domain.user.repository.UserRepository;
import com.kitschcatch.backend.domain.user.service.ProfileImageStorage;
import com.kitschcatch.backend.global.exception.BusinessException;
import com.kitschcatch.backend.global.exception.ErrorCode;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FollowService {
    private final UserRepository users;
    private final UserFollowRepository follows;
    private final ProfileImageStorage images;

    public FollowService(UserRepository users, UserFollowRepository follows, ProfileImageStorage images) {
        this.users = users;
        this.follows = follows;
        this.images = images;
    }

    @Transactional
    public FollowResponse change(long actorId, long targetId, boolean following) {
        validateTarget(targetId);
        if (actorId == targetId) throw new BusinessException(ErrorCode.BAD_REQUEST, "자기 자신은 팔로우할 수 없습니다.");
        // 양쪽 사용자 행을 같은 순서로 잠가 반대 방향 동시 요청과 FK 검사 간 교착을 피한다.
        var first = users.findByIdForUpdate(Math.min(actorId, targetId))
            .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
        var second = users.findByIdForUpdate(Math.max(actorId, targetId))
            .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
        if (following) {
            if (!follows.existsByFollowerIdAndFollowingId(actorId, targetId)) {
                var actor = first.getId() == actorId ? first : second;
                var target = first.getId() == targetId ? first : second;
                follows.saveAndFlush(new UserFollow(actor, target));
            }
        } else {
            follows.deleteFollow(actorId, targetId);
        }
        return new FollowResponse(targetId, following);
    }

    @Transactional(readOnly = true)
    public FollowPageResponse list(long actorId, long targetId, FollowPageQuery query, boolean followers) {
        validateTarget(targetId);
        if (!users.existsById(actorId) || (actorId != targetId && !users.existsById(targetId))) {
            throw new BusinessException(ErrorCode.USER_NOT_FOUND);
        }
        var pageable = PageRequest.of(query.page(), query.size());
        var page = followers ? follows.findFollowers(targetId, pageable) : follows.findFollowings(targetId, pageable);
        var content = page.getContent().stream().map(row -> new FollowPageResponse.FollowUser(
            row.id(), row.nickname(), row.profileImageKey() == null ? null : images.imageUrl(row.profileImageKey()))).toList();
        return new FollowPageResponse(content, page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages());
    }

    private void validateTarget(long targetId) {
        if (targetId <= 0) throw new BusinessException(ErrorCode.BAD_REQUEST);
    }
}
