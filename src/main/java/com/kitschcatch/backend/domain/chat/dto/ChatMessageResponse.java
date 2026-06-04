package com.kitschcatch.backend.domain.chat.dto;

import com.kitschcatch.backend.domain.chat.entity.ChatMessage;
import com.kitschcatch.backend.domain.chat.entity.MessageType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

@Schema(description = "채팅 메시지 응답")
public record ChatMessageResponse(

        @Schema(description = "채팅 메시지 ID", example = "1")
        Long messageId,

        @Schema(description = "채팅방 ID", example = "1")
        Long chatRoomId,

        @Schema(description = "발신자 ID", example = "2")
        Long senderId,

        @Schema(description = "발신자 닉네임", example = "buyer")
        String senderNickname,

        @Schema(description = "메시지 타입", example = "TEXT")
        MessageType messageType,

        @Schema(description = "텍스트 메시지 내용", example = "안녕하세요. 아직 구매 가능한가요?")
        String content,

        @Schema(
                description = "이미지 메시지 URL",
                example = "https://example-bucket.s3.ap-northeast-2.amazonaws.com/chats/1/2/550e8400-e29b-41d4-a716-446655440000.png"
        )
        String imageUrl,

        @Schema(description = "읽음 여부", example = "false")
        boolean isRead,

        @Schema(description = "메시지 생성 시각", example = "2026-06-04T14:30:00")
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