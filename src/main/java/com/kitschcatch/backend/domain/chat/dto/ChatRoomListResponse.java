package com.kitschcatch.backend.domain.chat.dto;

import com.kitschcatch.backend.domain.chat.entity.ChatRoom;
import com.kitschcatch.backend.domain.user.entity.User;
import java.time.LocalDateTime;


public record ChatRoomListResponse(
        Long chatRoomId,
        Long opponentId,
        String opponentNickname,
        String lastMessageContent,
        LocalDateTime lastMessageAt
) {


    public static ChatRoomListResponse from(ChatRoom chatRoom, Long currentUserId) {
        User opponent = findOpponent(chatRoom, currentUserId);

        return new ChatRoomListResponse(
                chatRoom.getId(),
                opponent.getId(),
                opponent.getNickname(),
                chatRoom.getLastMessageContent(),
                chatRoom.getLastMessageAt()
        );
    }

    /**
     * 현재 로그인 사용자를 기준으로 채팅 상대방을 찾는다.
     */
    private static User findOpponent(ChatRoom chatRoom, Long currentUserId) {
        if (chatRoom.getBuyer().getId().equals(currentUserId)) {
            return chatRoom.getSeller();
        }

        return chatRoom.getBuyer();
    }
}