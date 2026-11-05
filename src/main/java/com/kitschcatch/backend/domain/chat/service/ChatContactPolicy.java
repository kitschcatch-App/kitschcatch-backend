// 채팅 참여자 확인과 전송 직전의 사용자 간 차단 검사를 담당한다.
package com.kitschcatch.backend.domain.chat.service;

import com.kitschcatch.backend.domain.block.service.UserInteractionPolicy;
import com.kitschcatch.backend.domain.chat.entity.ChatRoom;
import com.kitschcatch.backend.domain.user.service.UserPairLock;
import com.kitschcatch.backend.global.exception.BusinessException;
import com.kitschcatch.backend.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ChatContactPolicy {
    private final UserPairLock userLocks;
    private final UserInteractionPolicy interactions;

    public void requireWritable(long actor, long target) {
        userLocks.lock(actor, target);
        requireAllowed(actor, target);
    }

    public void requireAllowed(long actor, long target) {
        userLocks.requireActiveTarget(target);
        interactions.requireAllowed(actor, target);
    }

    public static long opponentId(ChatRoom room, long actor) {
        if (room.getBuyer().getId() == actor) return room.getSeller().getId();
        if (room.getSeller().getId() == actor) return room.getBuyer().getId();
        throw new BusinessException(ErrorCode.CHAT_ROOM_ACCESS_DENIED);
    }
}
