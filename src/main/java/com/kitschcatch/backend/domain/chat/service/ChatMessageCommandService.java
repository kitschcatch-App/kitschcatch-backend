package com.kitschcatch.backend.domain.chat.service;

import com.kitschcatch.backend.domain.chat.dto.ChatMessageResponse;
import com.kitschcatch.backend.domain.chat.entity.ChatMessage;
import com.kitschcatch.backend.domain.chat.entity.ChatRoom;
import com.kitschcatch.backend.domain.chat.repository.ChatMessageRepository;
import com.kitschcatch.backend.domain.chat.repository.ChatRoomRepository;
import com.kitschcatch.backend.domain.user.entity.User;
import com.kitschcatch.backend.domain.user.repository.UserRepository;
import com.kitschcatch.backend.global.exception.BusinessException;
import com.kitschcatch.backend.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ChatMessageCommandService {

    private static final String IMAGE_MESSAGE_PREVIEW_TEXT = "사진을 보냈습니다.";

    private final ChatRoomRepository chatRoomRepository;
    private final UserRepository userRepository;
    private final ChatMessageRepository chatMessageRepository;

    @Transactional
    public ChatMessageResponse saveImageMessage(
            Long userId,
            Long chatRoomId,
            String imageUrl
    ) {
        ChatRoom chatRoom = chatRoomRepository.findById(chatRoomId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CHAT_ROOM_NOT_FOUND));

        User sender = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));

        ChatMessage chatMessage = chatMessageRepository.saveAndFlush(
                ChatMessage.createImageMessage(chatRoom, sender, imageUrl)
        );

        chatRoom.updateLastMessage(IMAGE_MESSAGE_PREVIEW_TEXT, chatMessage.getCreatedAt());

        return ChatMessageResponse.from(chatMessage);
    }
}