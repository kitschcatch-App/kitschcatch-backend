package com.kitschcatch.backend.domain.chat.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

@Schema(description = "채팅방 생성 요청")
public record CreateChatRoomRequest(

        @Schema(description = "채팅방을 생성할 판매 게시글 ID")
        @NotNull(message = "판매 게시글 ID는 필수입니다.")
        Long postId
) {
}
