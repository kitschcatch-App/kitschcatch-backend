package com.kitschcatch.backend.domain.chat.dto;

import com.kitschcatch.backend.domain.chat.entity.ChatRoom;


public record ChatRoomDetailResponse(
        Long chatRoomId,
        Long postId,
        String postTitle,
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