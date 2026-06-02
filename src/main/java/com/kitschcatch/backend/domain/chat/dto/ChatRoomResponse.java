package com.kitschcatch.backend.domain.chat.dto;

import com.kitschcatch.backend.domain.chat.entity.ChatRoom;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

@Schema(description = "채팅방 응답")
public record ChatRoomResponse(
        @Schema(description = "채팅방 ID")
        Long chatRoomId,

        @Schema(description = "판매 게시글 ID")
        Long postId,

        @Schema(description = "판매 게시글 제목")
        String postTitle,

        @Schema(description = "구매자 ID")
        Long buyerId,

        @Schema(description = "구매자 닉네임")
        String buyerNickname,

        @Schema(description = "판매자 ID")
        Long sellerId,

        @Schema(description = "판매자 닉네임")
        String sellerNickname,

        @Schema(description = "마지막 메시지 내용")
        String lastMessageContent,

        @Schema(description = "마지막 메시지 시각")
        LocalDateTime lastMessageAt,

        @Schema(description = "채팅방 생성 시각")
        LocalDateTime createdAt
) {

    public static ChatRoomResponse from(ChatRoom chatRoom) {
        return new ChatRoomResponse(
                chatRoom.getId(),
                chatRoom.getPost().getId(),
                chatRoom.getPost().getTitle(),
                chatRoom.getBuyer().getId(),
                chatRoom.getBuyer().getNickname(),
                chatRoom.getSeller().getId(),
                chatRoom.getSeller().getNickname(),
                chatRoom.getLastMessageContent(),
                chatRoom.getLastMessageAt(),
                chatRoom.getCreatedAt()
        );
    }
}
