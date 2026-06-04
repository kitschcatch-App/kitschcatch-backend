package com.kitschcatch.backend.domain.chat.storage;

import com.kitschcatch.backend.domain.chat.dto.ChatImageUploadUrl;

public interface ChatImageStorage {

    ChatImageUploadUrl createUploadUrl(
            Long userId,
            Long chatRoomId,
            String originalFileName,
            String contentType
    );

    boolean isOwnedChatImageKey(Long userId, Long chatRoomId, String objectKey);

    boolean exists(String objectKey);

    String imageUrl(String objectKey);
}
