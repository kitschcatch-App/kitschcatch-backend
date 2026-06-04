package com.kitschcatch.backend.domain.chat.dto;

import jakarta.validation.constraints.NotBlank;

public record ChatImageUploadUrlRequest(
        String originalFileName,

        @NotBlank(message = "이미지 Content-Type은 필수입니다.")
        String contentType
) {
}