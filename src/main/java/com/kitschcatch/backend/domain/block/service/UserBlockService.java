// 방향성 차단을 변경하고 로그인한 사용자의 차단 목록을 조회한다.
package com.kitschcatch.backend.domain.block.service;

import com.kitschcatch.backend.domain.block.dto.BlockPageResponse;
import com.kitschcatch.backend.domain.block.dto.BlockResponse;
import com.kitschcatch.backend.domain.block.entity.UserBlock;
import com.kitschcatch.backend.domain.block.repository.UserBlockRepository;
import com.kitschcatch.backend.domain.follow.repository.UserFollowRepository;
import com.kitschcatch.backend.domain.user.service.ProfileImageStorage;
import com.kitschcatch.backend.domain.user.service.UserPairLock;
import com.kitschcatch.backend.global.exception.BusinessException;
import com.kitschcatch.backend.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class UserBlockService {
    private final UserBlockRepository blocks;
    private final UserFollowRepository follows;
    private final UserPairLock userLocks;
    private final ProfileImageStorage images;

    @Transactional
    public BlockResponse change(long actor, long target, boolean block) {
        if (target <= 0 || actor == target) throw new BusinessException(ErrorCode.BAD_REQUEST);
        var pair = userLocks.lock(actor, target);
        if (block) {
            userLocks.requireActiveTarget(target);
            if (!blocks.existsByBlockerIdAndBlockedId(actor, target)) {
                blocks.save(new UserBlock(pair.actor(), pair.target()));
            }
            follows.deleteFollow(actor, target);
            follows.deleteFollow(target, actor);
        } else {
            blocks.deleteDirection(actor, target);
        }
        return new BlockResponse(target, block);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public BlockPageResponse list(long actor, int page, int size) {
        if (page < 0 || page > 10000 || size < 1 || size > 100) {
            throw new BusinessException(ErrorCode.INVALID_INPUT_VALUE);
        }
        userLocks.requireActiveActor(actor);
        var rows = blocks.findBlockedUsers(actor, PageRequest.of(page, size));
        var content = rows.getContent().stream().map(row -> new BlockPageResponse.BlockedUser(
            row.getUserId(), row.getNickname(),
            row.getProfileImageKey() == null ? null : images.imageUrl(row.getProfileImageKey()),
            row.getBlockedAt())).toList();
        return new BlockPageResponse(content, rows.getNumber(), rows.getSize(),
            rows.getTotalElements(), rows.getTotalPages());
    }
}
