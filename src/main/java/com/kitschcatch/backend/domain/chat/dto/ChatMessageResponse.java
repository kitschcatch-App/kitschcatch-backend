package com.kitschcatch.backend.domain.chat.dto;

import com.kitschcatch.backend.domain.chat.entity.ChatMessage;
import com.kitschcatch.backend.domain.chat.entity.MessageType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

@Schema(description = "채팅 메시지 응답")
public record ChatMessageResponse(
        @Schema(description = "채팅 메시지 ID")
        Long messageId,

        @Schema(description = "채팅방 ID")
        Long chatRoomId,

        @Schema(description = "발신자 ID")
        Long senderId,

        @Schema(description = "발신자 닉네임")
        String senderNickname,

        @Schema(description = "메시지 타입")
        MessageType messageType,

        @Schema(description = "텍스트 메시지 내용")
        String content,

        @Schema(description = "이미지 메시지 URL")
        String imageUrl,

        @Schema(description = "읽음 여부")
        boolean isRead,

        @Schema(description = "메시지 생성 시각")
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
