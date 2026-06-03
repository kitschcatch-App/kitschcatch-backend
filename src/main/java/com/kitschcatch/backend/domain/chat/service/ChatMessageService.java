package com.kitschcatch.backend.domain.chat.service;

import com.kitschcatch.backend.domain.chat.dto.ChatMessageResponse;
import com.kitschcatch.backend.domain.chat.dto.ChatReadResponse;
import com.kitschcatch.backend.domain.chat.entity.ChatMessage;
import com.kitschcatch.backend.domain.chat.entity.ChatRoom;
import com.kitschcatch.backend.domain.chat.repository.ChatMessageRepository;
import com.kitschcatch.backend.domain.chat.repository.ChatRoomRepository;
import com.kitschcatch.backend.domain.user.entity.User;
import com.kitschcatch.backend.domain.user.repository.UserRepository;
import com.kitschcatch.backend.global.exception.BusinessException;
import com.kitschcatch.backend.global.exception.ErrorCode;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

// HTTP와 STOMP 양쪽에서 재사용할 채팅 메시지 저장 및 조회 로직을 담당한다.
@Service
@RequiredArgsConstructor
public class ChatMessageService {

	private static final int MAX_TEXT_LENGTH = 1000;
	private static final String IMAGE_MESSAGE_PREVIEW_TEXT = "[이미지]";
	private static final String TEMP_CHAT_IMAGE_BASE_URL = "https://temp.kitschcatch.local/chat-images";

	private final ChatMessageRepository chatMessageRepository;
	private final ChatRoomRepository chatRoomRepository;
	private final UserRepository userRepository;


	@Transactional(readOnly = true)
	public List<ChatMessageResponse> getMessages(Long userId, Long chatRoomId) {
		// 채팅방 조회
		ChatRoom chatRoom = getChatRoom(chatRoomId);
		// 채팅방 참여자인지 확인
		validateChatRoomParticipant(chatRoom, userId);
		// 해당 채팅방의 메세지를 오름차순으로 조회
		return chatMessageRepository.findByChatRoomIdOrderByCreatedAtAsc(chatRoomId).stream()
			.map(ChatMessageResponse::from)
			.toList();
	}

	// 텍스트 메세지 저장
	@Transactional
	public ChatMessageResponse sendTextMessage(Long userId, Long chatRoomId, String content) {

		// 메세지 내용이 비어있는지 검사
		validateTextContent(content);
		// 채팅방 검증
		ChatRoom chatRoom = getChatRoom(chatRoomId);
		// 채팅방 참여자인지 검증
		validateChatRoomParticipant(chatRoom, userId);

		// 텍스트 엔티티 생성
		User sender = getUser(userId);
		ChatMessage chatMessage = chatMessageRepository.saveAndFlush(
			ChatMessage.createTextMessage(chatRoom, sender, content)
		);

		chatRoom.updateLastMessage(content, chatMessage.getCreatedAt());
		return ChatMessageResponse.from(chatMessage);
	}

	// 이미지 저장
	@Transactional
	public ChatMessageResponse sendImageMessage(Long userId, Long chatRoomId, MultipartFile imageFile) {

		// 이미지 파일 검증
		validateImageFile(imageFile);
		ChatRoom chatRoom = getChatRoom(chatRoomId);
		validateChatRoomParticipant(chatRoom, userId);


		// 이미지 엔티티 생성
		User sender = getUser(userId);
		String imageUrl = uploadChatImage(imageFile);
		ChatMessage chatMessage = chatMessageRepository.saveAndFlush(
			ChatMessage.createImageMessage(chatRoom, sender, imageUrl)
		);

		chatRoom.updateLastMessage(IMAGE_MESSAGE_PREVIEW_TEXT, chatMessage.getCreatedAt());
		return ChatMessageResponse.from(chatMessage);
	}

	@Transactional
	public ChatReadResponse markMessagesAsRead(Long userId, Long chatRoomId) {

		ChatRoom chatRoom = getChatRoom(chatRoomId);
		validateChatRoomParticipant(chatRoom, userId);

		// 상대방이 보낸 안읽은 메세지들 조회
		List<Long> unreadMessageIds = chatMessageRepository.findUnreadMessageIds(chatRoomId, userId);


		// 읽음 처리
		if (!unreadMessageIds.isEmpty()) {
			chatMessageRepository.markAsReadByIds(unreadMessageIds);
		}

		return new ChatReadResponse(
				chatRoomId,
				userId,
				unreadMessageIds,
				unreadMessageIds.size(),
				LocalDateTime.now()
		);
	}


	private ChatRoom getChatRoom(Long chatRoomId) {
		return chatRoomRepository.findChatRoomById(chatRoomId)
			.orElseThrow(() -> new BusinessException(ErrorCode.CHAT_ROOM_NOT_FOUND));
	}

	private User getUser(Long userId) {
		return userRepository.findById(userId)
			.orElseThrow(() -> new BusinessException(ErrorCode.INVALID_AUTH_TOKEN));
	}

	private void validateChatRoomParticipant(ChatRoom chatRoom, Long userId) {

		boolean isBuyer = chatRoom.getBuyer().getId().equals(userId);
		boolean isSeller = chatRoom.getSeller().getId().equals(userId);

		if (!isBuyer && !isSeller) {
			throw new BusinessException(ErrorCode.CHAT_ROOM_ACCESS_DENIED);
		}
	}

	private void validateTextContent(String content) {
		// 공백만 있는 메시지는 저장하지 않고 예외처리
		if (!StringUtils.hasText(content)) {
			throw new BusinessException(ErrorCode.MESSAGE_CONTENT_EMPTY);
		}

		if (content.length() > MAX_TEXT_LENGTH) {
			throw new BusinessException(ErrorCode.INVALID_INPUT_VALUE, "메시지 내용은 1000자 이하여야 합니다.");
		}
	}

	private void validateImageFile(MultipartFile imageFile) {

		if (imageFile == null || imageFile.isEmpty()) {
			throw new BusinessException(ErrorCode.IMAGE_FILE_EMPTY);
		}
		// 현재는 multipart 업로드만 받으므로 이미지 MIME 타입만 허용한다.
		if (!StringUtils.hasText(imageFile.getContentType()) || !imageFile.getContentType().startsWith("image/")) {
			throw new BusinessException(ErrorCode.INVALID_INPUT_VALUE, "이미지 파일만 전송할 수 있습니다.");
		}
	}

	private String uploadChatImage(MultipartFile imageFile) {
		// TODO: 프로젝트의 실제 채팅 이미지 업로드 서비스가 준비되면 이 메서드에서 교체한다.
		String originalFileName = StringUtils.hasText(imageFile.getOriginalFilename())
			? imageFile.getOriginalFilename()
			: "chat-image";
		String encodedFileName = URLEncoder.encode(originalFileName, StandardCharsets.UTF_8);

		return TEMP_CHAT_IMAGE_BASE_URL + "/" + UUID.randomUUID() + "-" + encodedFileName;
	}
}
