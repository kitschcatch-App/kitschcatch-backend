package com.kitschcatch.backend.domain.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.kitschcatch.backend.domain.chat.dto.ChatImageUploadUrl;
import com.kitschcatch.backend.domain.chat.dto.ChatMessageResponse;
import com.kitschcatch.backend.domain.chat.dto.ChatReadResponse;
import com.kitschcatch.backend.domain.chat.entity.ChatMessage;
import com.kitschcatch.backend.domain.chat.entity.ChatRoom;
import com.kitschcatch.backend.domain.chat.entity.MessageType;
import com.kitschcatch.backend.domain.chat.repository.ChatMessageRepository;
import com.kitschcatch.backend.domain.chat.repository.ChatRoomRepository;
import com.kitschcatch.backend.domain.chat.storage.ChatImageStorage;
import com.kitschcatch.backend.domain.post.entity.Post;
import com.kitschcatch.backend.domain.post.entity.ProductCategory;
import com.kitschcatch.backend.domain.post.entity.ProductCondition;
import com.kitschcatch.backend.domain.post.entity.ProductStatus;
import com.kitschcatch.backend.domain.user.entity.AuthProvider;
import com.kitschcatch.backend.domain.user.entity.User;
import com.kitschcatch.backend.domain.user.repository.UserRepository;
import com.kitschcatch.backend.global.exception.BusinessException;
import com.kitschcatch.backend.global.exception.ErrorCode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class ChatMessageServiceTest {

	private static final String CHAT_IMAGE_UPLOAD_URL =
		"https://upload.example.com/chats/100/1/chat-image.png?signature=abc";
	private static final String CHAT_IMAGE_OBJECT_KEY = "chats/100/1/chat-image.png";
	private static final String CHAT_IMAGE_URL = "https://cdn.example.com/chats/100/1/chat-image.png";
	private static final long CHAT_IMAGE_EXPIRES_IN_SECONDS = 300L;

	private ChatMessageRepository chatMessageRepository;
	private ChatRoomRepository chatRoomRepository;
	private UserRepository userRepository;
	private ChatImageStorage chatImageStorage;
	private ChatMessageCommandService chatMessageCommandService;
	private ChatMessageService chatMessageService;
	private User buyer;
	private User seller;
	private User outsider;
	private ChatRoom chatRoom;

	@BeforeEach
	void setUp() {
		chatMessageRepository = mock(ChatMessageRepository.class);
		chatRoomRepository = mock(ChatRoomRepository.class);
		userRepository = mock(UserRepository.class);
		chatImageStorage = mock(ChatImageStorage.class);
		chatMessageCommandService = mock(ChatMessageCommandService.class);

		chatMessageService = new ChatMessageService(
				chatMessageRepository,
				chatRoomRepository,
				userRepository,
				chatImageStorage,
				chatMessageCommandService
		);

		buyer = user(1L, "buyer", "buyer@example.com", "buyer-provider");
		seller = user(2L, "seller", "seller@example.com", "seller-provider");
		outsider = user(3L, "outsider", "outsider@example.com", "outsider-provider");
		chatRoom = chatRoom(100L, buyer, seller);
	}

	@Test
	@DisplayName("이전 메시지 조회는 채팅방 참여자에게 시간순 메시지 목록을 반환한다")
	void getMessagesReturnsStoredMessages() {
		ChatMessage firstMessage = ChatMessage.createTextMessage(chatRoom, buyer, "안녕하세요");
		ReflectionTestUtils.setField(firstMessage, "id", 11L);
		ReflectionTestUtils.setField(firstMessage, "createdAt", LocalDateTime.of(2026, 5, 24, 20, 0));

		ChatMessage secondMessage = ChatMessage.createTextMessage(chatRoom, seller, "네 문의 주세요");
		ReflectionTestUtils.setField(secondMessage, "id", 12L);
		ReflectionTestUtils.setField(secondMessage, "createdAt", LocalDateTime.of(2026, 5, 24, 20, 1));

		when(chatRoomRepository.findChatRoomById(100L)).thenReturn(Optional.of(chatRoom));
		when(chatMessageRepository.findByChatRoomIdOrderByCreatedAtAsc(100L))
			.thenReturn(List.of(firstMessage, secondMessage));

		List<ChatMessageResponse> responses = chatMessageService.getMessages(1L, 100L);

		assertThat(responses).hasSize(2);
		assertThat(responses.get(0).messageId()).isEqualTo(11L);
		assertThat(responses.get(0).senderNickname()).isEqualTo("buyer");
		assertThat(responses.get(1).messageId()).isEqualTo(12L);
		assertThat(responses.get(1).senderNickname()).isEqualTo("seller");
	}

	@Test
	@DisplayName("이전 메시지 조회는 참여자가 아니면 접근 거부 예외를 던진다")
	void getMessagesWithoutParticipantThrowsException() {
		when(chatRoomRepository.findChatRoomById(100L)).thenReturn(Optional.of(chatRoom));

		assertThatThrownBy(() -> chatMessageService.getMessages(3L, 100L))
			.isInstanceOf(BusinessException.class)
			.extracting("errorCode")
			.isEqualTo(ErrorCode.CHAT_ROOM_ACCESS_DENIED);
	}

	@Test
	@DisplayName("채팅방 참여자는 상대방이 보낸 안 읽은 메시지를 읽음 처리할 수 있다")
	void markMessagesAsReadMarksOpponentUnreadMessages() {
		List<Long> unreadMessageIds = List.of(10L, 11L, 12L);

		when(chatRoomRepository.findChatRoomById(100L)).thenReturn(Optional.of(chatRoom));
		when(chatMessageRepository.findUnreadMessageIds(100L, 1L)).thenReturn(unreadMessageIds);
		when(chatMessageRepository.markAsReadByIds(unreadMessageIds)).thenReturn(3);

		ChatReadResponse response = chatMessageService.markMessagesAsRead(1L, 100L);

		assertThat(response.chatRoomId()).isEqualTo(100L);
		assertThat(response.readerId()).isEqualTo(1L);
		assertThat(response.readMessageIds()).containsExactly(10L, 11L, 12L);
		assertThat(response.readCount()).isEqualTo(3);
		assertThat(response.readAt()).isNotNull();

		verify(chatRoomRepository).findChatRoomById(100L);
		verify(chatMessageRepository).findUnreadMessageIds(100L, 1L);
		verify(chatMessageRepository).markAsReadByIds(unreadMessageIds);
	}

	@Test
	@DisplayName("읽을 메시지가 없으면 읽음 처리 update를 수행하지 않는다")
	void markMessagesAsReadSkipsUpdateWhenNoUnreadMessages() {
		when(chatRoomRepository.findChatRoomById(100L)).thenReturn(Optional.of(chatRoom));
		when(chatMessageRepository.findUnreadMessageIds(100L, 1L)).thenReturn(List.of());

		ChatReadResponse response = chatMessageService.markMessagesAsRead(1L, 100L);

		assertThat(response.chatRoomId()).isEqualTo(100L);
		assertThat(response.readerId()).isEqualTo(1L);
		assertThat(response.readMessageIds()).isEmpty();
		assertThat(response.readCount()).isZero();
		assertThat(response.readAt()).isNotNull();

		verify(chatMessageRepository).findUnreadMessageIds(100L, 1L);
		verify(chatMessageRepository, never()).markAsReadByIds(anyList());
	}

	@Test
	@DisplayName("채팅방 참여자가 아니면 메시지를 읽음 처리할 수 없다")
	void markMessagesAsReadWithoutParticipantThrowsException() {
		when(chatRoomRepository.findChatRoomById(100L)).thenReturn(Optional.of(chatRoom));

		assertThatThrownBy(() -> chatMessageService.markMessagesAsRead(999L, 100L))
			.isInstanceOf(BusinessException.class)
			.extracting("errorCode")
			.isEqualTo(ErrorCode.CHAT_ROOM_ACCESS_DENIED);

		verify(chatMessageRepository, never()).findUnreadMessageIds(anyLong(), anyLong());
		verify(chatMessageRepository, never()).markAsReadByIds(anyList());
	}

	@Test
	@DisplayName("존재하지 않는 채팅방이면 메시지를 읽음 처리할 수 없다")
	void markMessagesAsReadWithoutChatRoomThrowsException() {
		when(chatRoomRepository.findChatRoomById(999L)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> chatMessageService.markMessagesAsRead(1L, 999L))
			.isInstanceOf(BusinessException.class)
			.extracting("errorCode")
			.isEqualTo(ErrorCode.CHAT_ROOM_NOT_FOUND);

		verify(chatMessageRepository, never()).findUnreadMessageIds(anyLong(), anyLong());
		verify(chatMessageRepository, never()).markAsReadByIds(anyList());
	}

	@Test
	@DisplayName("텍스트 메시지 전송은 메시지를 저장하고 채팅방 마지막 메시지를 갱신한다")
	void sendTextMessageSavesMessageAndUpdatesChatRoom() {
		when(chatRoomRepository.findChatRoomById(100L)).thenReturn(Optional.of(chatRoom));
		when(userRepository.findById(1L)).thenReturn(Optional.of(buyer));
		when(chatMessageRepository.saveAndFlush(any(ChatMessage.class))).thenAnswer(invocation -> {
			ChatMessage savedMessage = invocation.getArgument(0);
			ReflectionTestUtils.setField(savedMessage, "id", 200L);
			ReflectionTestUtils.setField(savedMessage, "createdAt", LocalDateTime.of(2026, 5, 24, 20, 30));
			return savedMessage;
		});

		ChatMessageResponse response = chatMessageService.sendTextMessage(1L, 100L, "구매 가능한가요?");

		assertThat(response.messageId()).isEqualTo(200L);
		assertThat(response.chatRoomId()).isEqualTo(100L);
		assertThat(response.senderId()).isEqualTo(1L);
		assertThat(response.content()).isEqualTo("구매 가능한가요?");
		assertThat(chatRoom.getLastMessageContent()).isEqualTo("구매 가능한가요?");
		assertThat(chatRoom.getLastMessageAt()).isEqualTo(LocalDateTime.of(2026, 5, 24, 20, 30));
	}

	@Test
	@DisplayName("텍스트 메시지 전송은 공백 메시지를 허용하지 않는다")
	void sendTextMessageWithBlankContentThrowsException() {
		when(chatRoomRepository.findChatRoomById(100L)).thenReturn(Optional.of(chatRoom));

		assertThatThrownBy(() -> chatMessageService.sendTextMessage(1L, 100L, "   "))
			.isInstanceOf(BusinessException.class)
			.extracting("errorCode")
			.isEqualTo(ErrorCode.MESSAGE_CONTENT_EMPTY);
	}

	@Test
	@DisplayName("채팅방 참여자는 채팅 이미지 업로드 URL을 발급받을 수 있다")
	void createChatImageUploadUrlReturnsUploadUrlForParticipant() {
		when(chatRoomRepository.findChatRoomById(100L)).thenReturn(Optional.of(chatRoom));
		when(chatImageStorage.createUploadUrl(1L, 100L, "chat.png", "image/png"))
			.thenReturn(new ChatImageUploadUrl(
				CHAT_IMAGE_UPLOAD_URL,
				CHAT_IMAGE_OBJECT_KEY,
				CHAT_IMAGE_URL,
				CHAT_IMAGE_EXPIRES_IN_SECONDS
			));

		ChatImageUploadUrl response = chatMessageService.createChatImageUploadUrl(
			1L,
			100L,
			"chat.png",
			"image/png"
		);

		assertThat(response.uploadUrl()).isEqualTo(CHAT_IMAGE_UPLOAD_URL);
		assertThat(response.objectKey()).isEqualTo(CHAT_IMAGE_OBJECT_KEY);
		assertThat(response.imageUrl()).isEqualTo(CHAT_IMAGE_URL);
		assertThat(response.expiresInSeconds()).isEqualTo(CHAT_IMAGE_EXPIRES_IN_SECONDS);

		verify(chatImageStorage).createUploadUrl(1L, 100L, "chat.png", "image/png");
	}

	@Test
	@DisplayName("채팅방 참여자가 아니면 채팅 이미지 업로드 URL을 발급받을 수 없다")
	void createChatImageUploadUrlWithoutParticipantThrowsException() {
		when(chatRoomRepository.findChatRoomById(100L)).thenReturn(Optional.of(chatRoom));

		assertThatThrownBy(() -> chatMessageService.createChatImageUploadUrl(3L, 100L, "chat.png", "image/png"))
			.isInstanceOf(BusinessException.class)
			.extracting("errorCode")
			.isEqualTo(ErrorCode.CHAT_ROOM_ACCESS_DENIED);

		verify(chatImageStorage, never()).createUploadUrl(anyLong(), anyLong(), anyString(), anyString());
	}

	@Test
	@DisplayName("업로드된 채팅 이미지 objectKey로 이미지 메시지를 저장할 수 있다")
	void sendImageMessageSavesImageMessageFromUploadedObjectKey() {
		LocalDateTime createdAt = LocalDateTime.of(2026, 5, 24, 20, 40);

		ChatMessageResponse commandResponse = new ChatMessageResponse(
				300L,
				100L,
				1L,
				"buyer",
				MessageType.IMAGE,
				null,
				CHAT_IMAGE_URL,
				false,
				createdAt
		);

		when(chatRoomRepository.findChatRoomById(100L)).thenReturn(Optional.of(chatRoom));
		when(chatImageStorage.isOwnedChatImageKey(1L, 100L, CHAT_IMAGE_OBJECT_KEY)).thenReturn(true);
		when(chatImageStorage.exists(CHAT_IMAGE_OBJECT_KEY)).thenReturn(true);
		when(chatImageStorage.imageUrl(CHAT_IMAGE_OBJECT_KEY)).thenReturn(CHAT_IMAGE_URL);
		when(chatMessageCommandService.saveImageMessage(1L, 100L, CHAT_IMAGE_URL))
				.thenReturn(commandResponse);

		ChatMessageResponse response = chatMessageService.sendImageMessage(1L, 100L, CHAT_IMAGE_OBJECT_KEY);

		assertThat(response.messageId()).isEqualTo(300L);
		assertThat(response.messageType()).isEqualTo(MessageType.IMAGE);
		assertThat(response.imageUrl()).isEqualTo(CHAT_IMAGE_URL);

		verify(chatImageStorage).exists(CHAT_IMAGE_OBJECT_KEY);
		verify(chatImageStorage).imageUrl(CHAT_IMAGE_OBJECT_KEY);
		verify(chatMessageCommandService).saveImageMessage(1L, 100L, CHAT_IMAGE_URL);
		verify(chatMessageRepository, never()).saveAndFlush(any(ChatMessage.class));
	}

	@Test
	@DisplayName("본인이 발급받지 않은 objectKey로는 이미지 메시지를 저장할 수 없다")
	void sendImageMessageWithForeignObjectKeyThrowsException() {
		when(chatRoomRepository.findChatRoomById(100L)).thenReturn(Optional.of(chatRoom));
		when(chatImageStorage.isOwnedChatImageKey(1L, 100L, CHAT_IMAGE_OBJECT_KEY)).thenReturn(false);

		assertThatThrownBy(() -> chatMessageService.sendImageMessage(1L, 100L, CHAT_IMAGE_OBJECT_KEY))
			.isInstanceOf(BusinessException.class)
			.extracting("errorCode")
			.isEqualTo(ErrorCode.CHAT_IMAGE_FORBIDDEN);

		verify(chatImageStorage, never()).exists(anyString());
		verify(chatMessageRepository, never()).saveAndFlush(any(ChatMessage.class));
	}

	@Test
	@DisplayName("존재하지 않는 S3 objectKey로는 이미지 메시지를 저장할 수 없다")
	void sendImageMessageWithMissingObjectKeyThrowsException() {
		when(chatRoomRepository.findChatRoomById(100L)).thenReturn(Optional.of(chatRoom));
		when(chatImageStorage.isOwnedChatImageKey(1L, 100L, CHAT_IMAGE_OBJECT_KEY)).thenReturn(true);
		when(chatImageStorage.exists(CHAT_IMAGE_OBJECT_KEY)).thenReturn(false);

		assertThatThrownBy(() -> chatMessageService.sendImageMessage(1L, 100L, CHAT_IMAGE_OBJECT_KEY))
			.isInstanceOf(BusinessException.class)
			.extracting("errorCode")
			.isEqualTo(ErrorCode.CHAT_IMAGE_NOT_FOUND);

		verify(chatMessageRepository, never()).saveAndFlush(any(ChatMessage.class));
	}


	private User user(Long id, String nickname, String email, String providerUserId) {
		User user = User.builder()
			.nickname(nickname)
			.email(email)
			.authProvider(AuthProvider.KAKAO)
			.providerUserId(providerUserId)
			.build();
		ReflectionTestUtils.setField(user, "id", id);
		return user;
	}

	private ChatRoom chatRoom(Long id, User buyer, User seller) {
		Post post = Post.builder()
			.user(seller)
			.title("키링 판매")
			.description("미개봉 상품입니다.")
			.price(12000L)
			.productCategory(ProductCategory.GOODS)
			.productCondition(ProductCondition.NEW)
			.productStatus(ProductStatus.ON_SALE)
			.build();
		ReflectionTestUtils.setField(post, "id", 10L);

		ChatRoom chatRoom = ChatRoom.create(post, buyer, seller);
		ReflectionTestUtils.setField(chatRoom, "id", id);
		return chatRoom;
	}
}
