package com.kitschcatch.backend.domain.chat.dto;

import jakarta.validation.constraints.NotBlank;

public record SendChatImageMessageRequest(
        @NotBlank(message = "이미지 objectKey는 필수입니다.")
        String objectKey
) {
}