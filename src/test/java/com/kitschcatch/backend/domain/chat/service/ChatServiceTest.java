package com.kitschcatch.backend.domain.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.kitschcatch.backend.domain.chat.dto.ChatRoomListResponse;
import com.kitschcatch.backend.domain.chat.dto.ChatRoomResponse;
import com.kitschcatch.backend.domain.chat.dto.CreateChatRoomRequest;
import com.kitschcatch.backend.domain.chat.entity.ChatRoom;
import com.kitschcatch.backend.domain.chat.repository.ChatRoomRepository;
import com.kitschcatch.backend.domain.post.entity.Post;
import com.kitschcatch.backend.domain.post.entity.ProductCategory;
import com.kitschcatch.backend.domain.post.entity.ProductCondition;
import com.kitschcatch.backend.domain.post.entity.ProductStatus;
import com.kitschcatch.backend.domain.post.repository.PostRepository;
import com.kitschcatch.backend.domain.post.service.PostImageStorage;
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
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

class ChatServiceTest {

	private ChatRoomRepository chatRoomRepository;
	private PostRepository postRepository;
	private UserRepository userRepository;
	private PostImageStorage postImageStorage;
	private ChatService chatService;
	private User currentUser;
	private User seller;
	private Post post;

	@BeforeEach
	void setUp() {
		chatRoomRepository = mock(ChatRoomRepository.class);
		postRepository = mock(PostRepository.class);
		userRepository = mock(UserRepository.class);
		postImageStorage = mock(PostImageStorage.class);
		chatService = new ChatService(chatRoomRepository, postRepository, userRepository, postImageStorage);

		currentUser = user(1L, "buyer", "buyer@example.com", "buyer-provider");
		seller = user(2L, "seller", "seller@example.com", "seller-provider");
		post = post(10L, seller);
	}

	@Test
	@DisplayName("채팅방 생성은 게시글 구매자 판매자로 새 채팅방을 저장한다")
	void createChatRoomCreatesNewRoom() {
		when(postRepository.findByIdAndDeletedAtIsNull(10L)).thenReturn(Optional.of(post));
		when(userRepository.findById(1L)).thenReturn(Optional.of(currentUser));
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
		assertThat(savedRoom.getBuyer()).isEqualTo(currentUser);
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
		ChatRoom existingRoom = ChatRoom.create(post, currentUser, seller);
		ReflectionTestUtils.setField(existingRoom, "id", 77L);
		ReflectionTestUtils.setField(existingRoom, "createdAt", LocalDateTime.of(2026, 5, 22, 19, 0));

		when(postRepository.findByIdAndDeletedAtIsNull(10L)).thenReturn(Optional.of(post));
		when(userRepository.findById(1L)).thenReturn(Optional.of(currentUser));
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

	@Test
	@DisplayName("buyer와 seller로 참여한 채팅방은 상대방 닉네임과 대표 이미지 URL로 매핑된다")
	void getMyChatRoomsMapsOpponentNicknameAndThumbnailImageUrl() {
		// 현재 사용자가 buyer로 참여한 채팅방과 seller로 참여한 채팅방을 준비한다.
		User otherBuyer = user(3L, "another-buyer", "another-buyer@example.com", "another-buyer-provider");
		User otherSeller = user(4L, "another-seller", "another-seller@example.com", "another-seller-provider");

		Post buyerPost = post(11L, otherSeller);
		buyerPost.addImage("posts/11/thumbnail.png", 0);

		Post sellerPost = post(12L, currentUser);
		sellerPost.addImage("posts/12/thumbnail.png", 0);

		ChatRoom buyerChatRoom = ChatRoom.create(buyerPost, currentUser, otherSeller);
		ReflectionTestUtils.setField(buyerChatRoom, "id", 101L);
		ReflectionTestUtils.setField(buyerChatRoom, "createdAt", LocalDateTime.of(2026, 5, 24, 10, 0));
		buyerChatRoom.updateLastMessage("판매 중인가요?", LocalDateTime.of(2026, 5, 24, 10, 5));

		ChatRoom sellerChatRoom = ChatRoom.create(sellerPost, otherBuyer, currentUser);
		ReflectionTestUtils.setField(sellerChatRoom, "id", 102L);
		ReflectionTestUtils.setField(sellerChatRoom, "createdAt", LocalDateTime.of(2026, 5, 24, 9, 0));
		sellerChatRoom.updateLastMessage("네 가능합니다.", LocalDateTime.of(2026, 5, 24, 9, 30));

		// 저장소는 이미 정렬된 채팅방 목록을 반환한다고 가정하고 서비스의 매핑 책임만 검증한다.
		when(chatRoomRepository.findMyChatRooms(1L)).thenReturn(List.of(buyerChatRoom, sellerChatRoom));
		when(postImageStorage.imageUrl("posts/11/thumbnail.png"))
			.thenReturn("https://cdn.test/posts/11/thumbnail.png");
		when(postImageStorage.imageUrl("posts/12/thumbnail.png"))
			.thenReturn("https://cdn.test/posts/12/thumbnail.png");

		List<ChatRoomListResponse> responses = chatService.getMyChatRooms(1L);

		assertThat(responses).hasSize(2);
		assertThat(responses.get(0).chatRoomId()).isEqualTo(101L);
		assertThat(responses.get(0).opponentNickname()).isEqualTo("another-seller");
		assertThat(responses.get(0).postThumbnailImageUrl()).isEqualTo("https://cdn.test/posts/11/thumbnail.png");
		assertThat(responses.get(1).chatRoomId()).isEqualTo(102L);
		assertThat(responses.get(1).opponentNickname()).isEqualTo("another-buyer");
		assertThat(responses.get(1).postThumbnailImageUrl()).isEqualTo("https://cdn.test/posts/12/thumbnail.png");
	}

	@Test
	@DisplayName("대표 이미지가 없는 채팅방은 썸네일 URL을 null로 반환한다")
	void getMyChatRoomsReturnsNullWhenPostHasNoThumbnailImage() {
		// 판매글 이미지가 없을 때는 S3 URL 생성 없이 null을 내려줘야 한다.
		User otherSeller = user(4L, "another-seller", "another-seller@example.com", "another-seller-provider");
		Post imageLessPost = post(13L, otherSeller);
		ChatRoom buyerChatRoom = ChatRoom.create(imageLessPost, currentUser, otherSeller);
		ReflectionTestUtils.setField(buyerChatRoom, "id", 103L);
		ReflectionTestUtils.setField(buyerChatRoom, "createdAt", LocalDateTime.of(2026, 5, 24, 8, 0));
		buyerChatRoom.updateLastMessage("이미지 있나요?", LocalDateTime.of(2026, 5, 24, 8, 10));

		when(chatRoomRepository.findMyChatRooms(1L)).thenReturn(List.of(buyerChatRoom));

		List<ChatRoomListResponse> responses = chatService.getMyChatRooms(1L);

		assertThat(responses).hasSize(1);
		assertThat(responses.getFirst().postThumbnailImageUrl()).isNull();
		verify(postImageStorage, never()).imageUrl(any());
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
