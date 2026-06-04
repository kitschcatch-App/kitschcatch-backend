package com.kitschcatch.backend.domain.chat.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

@Schema(description = "채팅 이미지 업로드 URL 발급 요청")
public record ChatImageUploadUrlRequest(

        @Schema(description = "원본 이미지 파일명", example = "chat-image.png")
        String originalFileName,

        @Schema(description = "이미지 Content-Type", example = "image/png")
        @NotBlank(message = "이미지 Content-Type은 필수입니다.")
        String contentType
) {
}