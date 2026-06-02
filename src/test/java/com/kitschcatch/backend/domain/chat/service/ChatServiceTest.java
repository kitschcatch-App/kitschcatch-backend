package com.kitschcatch.backend.domain.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.kitschcatch.backend.domain.chat.dto.ChatRoomResponse;
import com.kitschcatch.backend.domain.chat.dto.CreateChatRoomRequest;
import com.kitschcatch.backend.domain.chat.entity.ChatRoom;
import com.kitschcatch.backend.domain.chat.repository.ChatRoomRepository;
import com.kitschcatch.backend.domain.post.entity.Post;
import com.kitschcatch.backend.domain.post.entity.ProductCategory;
import com.kitschcatch.backend.domain.post.entity.ProductCondition;
import com.kitschcatch.backend.domain.post.entity.ProductStatus;
import com.kitschcatch.backend.domain.post.repository.PostRepository;
import com.kitschcatch.backend.domain.user.entity.AuthProvider;
import com.kitschcatch.backend.domain.user.entity.User;
import com.kitschcatch.backend.domain.user.repository.UserRepository;
import com.kitschcatch.backend.global.exception.BusinessException;
import com.kitschcatch.backend.global.exception.ErrorCode;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

class ChatServiceTest {

	private ChatRoomRepository chatRoomRepository;
	private PostRepository postRepository;
	private UserRepository userRepository;
	private ChatService chatService;
	private User buyer;
	private User seller;
	private Post post;

	@BeforeEach
	void setUp() {
		chatRoomRepository = mock(ChatRoomRepository.class);
		postRepository = mock(PostRepository.class);
		userRepository = mock(UserRepository.class);
		chatService = new ChatService(chatRoomRepository, postRepository, userRepository);

		buyer = user(1L, "buyer", "buyer@example.com", "buyer-provider");
		seller = user(2L, "seller", "seller@example.com", "seller-provider");
		post = post(10L, seller);
	}

	@Test
	@DisplayName("채팅방 생성은 게시글 구매자 판매자로 새 채팅방을 저장한다")
	void createChatRoomCreatesNewRoom() {
		when(postRepository.findByIdAndDeletedAtIsNull(10L)).thenReturn(Optional.of(post));
		when(userRepository.findById(1L)).thenReturn(Optional.of(buyer));
		when(chatRoomRepository.findByPostIdAndBuyerIdAndSellerId(10L, 1L, 2L)).thenReturn(Optional.empty());
		when(chatRoomRepository.save(any(ChatRoom.class))).thenAnswer(invocation -> {
			ChatRoom saved = invocation.getArgument(0);
			ReflectionTestUtils.setField(saved, "id", 100L);
			ReflectionTestUtils.setField(saved, "createdAt", LocalDateTime.of(2026, 5, 22, 20, 30));
			return saved;
		});

		ChatRoomResponse response = chatService.createChatRoom(1L, new CreateChatRoomRequest(10L));

		ArgumentCaptor<ChatRoom> chatRoomCaptor = ArgumentCaptor.forClass(ChatRoom.class);
		verify(chatRoomRepository).save(chatRoomCaptor.capture());
		ChatRoom savedRoom = chatRoomCaptor.getValue();

		assertThat(savedRoom.getPost()).isEqualTo(post);
		assertThat(savedRoom.getBuyer()).isEqualTo(buyer);
		assertThat(savedRoom.getSeller()).isEqualTo(seller);
		assertThat(response.chatRoomId()).isEqualTo(100L);
		assertThat(response.postId()).isEqualTo(10L);
		assertThat(response.buyerId()).isEqualTo(1L);
		assertThat(response.sellerId()).isEqualTo(2L);
		assertThat(response.buyerNickname()).isEqualTo("buyer");
		assertThat(response.sellerNickname()).isEqualTo("seller");
	}

	@Test
	@DisplayName("채팅방 생성은 이미 같은 조합의 방이 있으면 기존 채팅방을 반환한다")
	void createChatRoomReturnsExistingRoom() {
		ChatRoom existingRoom = ChatRoom.create(post, buyer, seller);
		ReflectionTestUtils.setField(existingRoom, "id", 77L);
		ReflectionTestUtils.setField(existingRoom, "createdAt", LocalDateTime.of(2026, 5, 22, 19, 0));

		when(postRepository.findByIdAndDeletedAtIsNull(10L)).thenReturn(Optional.of(post));
		when(userRepository.findById(1L)).thenReturn(Optional.of(buyer));
		when(chatRoomRepository.findByPostIdAndBuyerIdAndSellerId(10L, 1L, 2L))
			.thenReturn(Optional.of(existingRoom));

		ChatRoomResponse response = chatService.createChatRoom(1L, new CreateChatRoomRequest(10L));

		verify(chatRoomRepository, never()).save(any(ChatRoom.class));
		assertThat(response.chatRoomId()).isEqualTo(77L);
		assertThat(response.postTitle()).isEqualTo("키링 판매");
		assertThat(response.createdAt()).isEqualTo(LocalDateTime.of(2026, 5, 22, 19, 0));
	}

	@Test
	@DisplayName("채팅방 생성은 게시글이 없으면 예외를 던진다")
	void createChatRoomWithoutPostThrowsException() {
		when(postRepository.findByIdAndDeletedAtIsNull(10L)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> chatService.createChatRoom(1L, new CreateChatRoomRequest(10L)))
			.isInstanceOf(BusinessException.class)
			.extracting("errorCode")
			.isEqualTo(ErrorCode.POST_NOT_FOUND);
	}

	@Test
	@DisplayName("채팅방 생성은 인증 사용자가 없으면 예외를 던진다")
	void createChatRoomWithoutBuyerThrowsException() {
		when(postRepository.findByIdAndDeletedAtIsNull(10L)).thenReturn(Optional.of(post));
		when(userRepository.findById(1L)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> chatService.createChatRoom(1L, new CreateChatRoomRequest(10L)))
			.isInstanceOf(BusinessException.class)
			.extracting("errorCode")
			.isEqualTo(ErrorCode.INVALID_AUTH_TOKEN);
	}

	@Test
	@DisplayName("채팅방 생성은 자신의 판매 게시글에는 문의할 수 없다")
	void createChatRoomForOwnPostThrowsException() {
		User sameUser = user(2L, "seller", "seller@example.com", "seller-provider");
		when(postRepository.findByIdAndDeletedAtIsNull(10L)).thenReturn(Optional.of(post));
		when(userRepository.findById(2L)).thenReturn(Optional.of(sameUser));

		assertThatThrownBy(() -> chatService.createChatRoom(2L, new CreateChatRoomRequest(10L)))
			.isInstanceOf(BusinessException.class)
			.extracting("errorCode")
			.isEqualTo(ErrorCode.CHAT_ROOM_SELF_NOT_ALLOWED);
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

	private Post post(Long id, User seller) {
		Post post = Post.builder()
			.user(seller)
			.title("키링 판매")
			.description("미개봉 굿즈입니다.")
			.price(12000L)
			.productCategory(ProductCategory.GOODS)
			.productCondition(ProductCondition.NEW)
			.productStatus(ProductStatus.ON_SALE)
			.build();
		ReflectionTestUtils.setField(post, "id", id);
		return post;
	}
}
