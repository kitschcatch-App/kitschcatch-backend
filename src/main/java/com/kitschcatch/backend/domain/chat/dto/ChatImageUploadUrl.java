package com.kitschcatch.backend.domain.chat.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "채팅 이미지 업로드 URL 발급 응답")
public record ChatImageUploadUrl(

        @Schema(
                description = "S3에 이미지를 업로드할 수 있는 Presigned URL",
                example = "https://example-bucket.s3.ap-northeast-2.amazonaws.com/chats/1/1/uuid.png?X-Amz-Algorithm=AWS4-HMAC-SHA256"
        )
        String uploadUrl,

        @Schema(
                description = "S3 객체 키",
                example = "chats/1/1/550e8400-e29b-41d4-a716-446655440000.png"
        )
        String objectKey,

        @Schema(
                description = "업로드 완료 후 접근 가능한 이미지 URL",
                example = "https://example-bucket.s3.ap-northeast-2.amazonaws.com/chats/1/1/550e8400-e29b-41d4-a716-446655440000.png"
        )
        String imageUrl,

        @Schema(description = "Presigned URL 만료 시간, 초 단위", example = "300")
        long expiresInSeconds
) {
}