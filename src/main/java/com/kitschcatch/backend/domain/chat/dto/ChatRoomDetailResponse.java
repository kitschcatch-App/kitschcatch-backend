package com.kitschcatch.backend.domain.chat.dto;

import com.kitschcatch.backend.domain.chat.entity.ChatRoom;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "채팅방 상세 조회 응답")
public record ChatRoomDetailResponse(

        @Schema(description = "채팅방 ID", example = "1")
        Long chatRoomId,

        @Schema(description = "판매 게시글 ID", example = "10")
        Long postId,

        @Schema(description = "판매 게시글 제목", example = "스파이패밀리 아냐 키링 판매합니다")
        String postTitle,

        @Schema(
                description = "판매 게시글 대표 이미지 S3 GET 서명 URL. 기본 15분 유효하며 만료되면 채팅방 상세를 다시 조회합니다.",
                example = "https://example-bucket.s3.ap-northeast-2.amazonaws.com/posts/1/thumbnail.png?X-Amz-Signature=..."
        )
        String postThumbnailImageUrl
) {

    public static ChatRoomDetailResponse from(
            ChatRoom chatRoom,
            String postThumbnailImageUrl
    ) {
        return new ChatRoomDetailResponse(
                chatRoom.getId(),
                chatRoom.getPost().getId(),
                chatRoom.getPost().getTitle(),
                postThumbnailImageUrl
        );
    }
}
