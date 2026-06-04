package com.kitschcatch.backend.domain.chat.dto;

import com.kitschcatch.backend.domain.chat.entity.ChatRoom;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

@Schema(description = "채팅방 생성 응답")
public record ChatRoomResponse(

        @Schema(description = "채팅방 ID", example = "1")
        Long chatRoomId,

        @Schema(description = "판매 게시글 ID", example = "10")
        Long postId,

        @Schema(description = "판매 게시글 제목", example = "스파이패밀리 아냐 키링 판매합니다")
        String postTitle,

        @Schema(description = "구매자 ID", example = "2")
        Long buyerId,

        @Schema(description = "판매자 ID", example = "3")
        Long sellerId,

        @Schema(description = "마지막 메시지 내용", example = "사진을 보냈습니다.")
        String lastMessageContent,

        @Schema(description = "마지막 메시지 시간", example = "2026-06-04T14:30:00")
        LocalDateTime lastMessageAt,

        @Schema(description = "채팅방 생성 시각", example = "2026-06-04T14:20:00")
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