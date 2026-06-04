package com.kitschcatch.backend.domain.chat.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

@Schema(description = "이미지 메시지 저장 요청")
public record SendChatImageMessageRequest(

        @Schema(
                description = "S3에 업로드된 이미지 객체 키",
                example = "chats/1/2/550e8400-e29b-41d4-a716-446655440000.png"
        )
        @NotBlank(message = "이미지 objectKey는 필수입니다.")
        String objectKey
) {
}