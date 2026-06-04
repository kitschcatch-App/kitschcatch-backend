package com.kitschcatch.backend.domain.chat.dto;

import com.kitschcatch.backend.domain.chat.entity.ChatRoom;
import com.kitschcatch.backend.domain.user.entity.User;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

@Schema(description = "채팅방 목록 조회 응답")
public record ChatRoomListResponse(

        @Schema(description = "채팅방 ID", example = "1")
        Long chatRoomId,

        @Schema(description = "상대방 ID", example = "2")
        Long opponentId,

        @Schema(description = "상대방 닉네임", example = "seller")
        String opponentNickname,

        @Schema(description = "마지막 메시지 내용", example = "사진을 보냈습니다.")
        String lastMessageContent,

        @Schema(description = "마지막 메시지 시간", example = "2026-06-04T14:30:00")
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

    private static User findOpponent(ChatRoom chatRoom, Long currentUserId) {
        if (chatRoom.getBuyer().getId().equals(currentUserId)) {
            return chatRoom.getSeller();
        }

        return chatRoom.getBuyer();
    }
}