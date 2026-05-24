package com.kitschcatch.backend.domain.chat.dto;

import jakarta.validation.constraints.NotNull;

public record CreateChatRoomRequest(

        @NotNull(message = "판매 게시글 ID는 필수입니다.")
        Long postId
) {
}