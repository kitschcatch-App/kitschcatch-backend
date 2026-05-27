package com.kitschcatch.backend.domain.chat.dto;

import com.kitschcatch.backend.domain.chat.entity.ChatMessage;
import com.kitschcatch.backend.domain.chat.entity.MessageType;
import java.time.LocalDateTime;

public record ChatMessageResponse(
        Long messageId,
        Long chatRoomId,
        Long senderId,
        String senderNickname,
        MessageType messageType,
        String content,
        String imageUrl,
        boolean isRead,
        LocalDateTime createdAt
) {

    public static ChatMessageResponse from(ChatMessage chatMessage) {
        return new ChatMessageResponse(
                chatMessage.getId(),
                chatMessage.getChatRoom().getId(),
                chatMessage.getSender().getId(),
                chatMessage.getSender().getNickname(),
                chatMessage.getMessageType(),
                chatMessage.getContent(),
                chatMessage.getImageUrl(),
                chatMessage.isRead(),
                chatMessage.getCreatedAt()
        );
    }
}
