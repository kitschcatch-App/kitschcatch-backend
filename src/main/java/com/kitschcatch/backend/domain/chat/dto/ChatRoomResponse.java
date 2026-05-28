package com.kitschcatch.backend.domain.chat.dto;

import com.kitschcatch.backend.domain.chat.entity.ChatRoom;
import java.time.LocalDateTime;

public record ChatRoomResponse(
        Long chatRoomId,
        Long postId,
        String postTitle,
        Long buyerId,
        Long sellerId,
        String lastMessageContent,
        LocalDateTime lastMessageAt,
        LocalDateTime createdAt
) {

    public static ChatRoomResponse from(ChatRoom chatRoom) {
        return new ChatRoomResponse(
                chatRoom.getId(),
                chatRoom.getPost().getId(),
                chatRoom.getPost().getTitle(),
                chatRoom.getBuyer().getId(),
                chatRoom.getSeller().getId(),
                chatRoom.getLastMessageContent(),
                chatRoom.getLastMessageAt(),
                chatRoom.getCreatedAt()
        );
    }
}