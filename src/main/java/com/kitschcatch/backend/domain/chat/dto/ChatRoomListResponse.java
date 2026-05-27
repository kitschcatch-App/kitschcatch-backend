package com.kitschcatch.backend.domain.chat.dto;

import com.kitschcatch.backend.domain.chat.entity.ChatRoom;
import com.kitschcatch.backend.domain.post.entity.ProductStatus;
import com.kitschcatch.backend.domain.user.entity.User;
import java.time.LocalDateTime;


// 채팅방 목록 조회 응답 DTO
public record ChatRoomListResponse(
        Long chatRoomId,
        Long postId,
        String postTitle,
        Long postPrice,
        ProductStatus postStatus,
        String postThumbnailImageUrl,
        Long opponentId,
        String opponentNickname,
        String lastMessageContent,
        LocalDateTime lastMessageAt,
        LocalDateTime createdAt
) {

    public static ChatRoomListResponse from(
            ChatRoom chatRoom,
            Long currentUserId,
            String postThumbnailImageUrl
    ) {
        User opponent = findOpponent(chatRoom, currentUserId);

        return new ChatRoomListResponse(
                chatRoom.getId(),
                chatRoom.getPost().getId(),
                chatRoom.getPost().getTitle(),
                chatRoom.getPost().getPrice(),
                chatRoom.getPost().getProductStatus(),
                postThumbnailImageUrl,
                opponent.getId(),
                opponent.getNickname(),
                chatRoom.getLastMessageContent(),
                chatRoom.getLastMessageAt(),
                chatRoom.getCreatedAt()
        );
    }

    /**
     * 현재 로그인한 사용자를 기준으로 채팅 상대방을 찾는다.
     */
    private static User findOpponent(ChatRoom chatRoom, Long currentUserId) {
        if (chatRoom.getBuyer().getId().equals(currentUserId)) {
            return chatRoom.getSeller();
        }

        return chatRoom.getBuyer();
    }
}