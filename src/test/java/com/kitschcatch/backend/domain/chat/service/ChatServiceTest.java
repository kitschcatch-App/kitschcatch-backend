package com.kitschcatch.backend.domain.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.kitschcatch.backend.domain.chat.dto.ChatRoomDetailResponse;
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
	private User buyer;
	private User seller;
	private User stranger;
	private Post post;

	@BeforeEach
	void setUp() {
		chatRoomRepository = mock(ChatRoomRepository.class);
		postRepository = mock(PostRepository.class);
		userRepository = mock(UserRepository.class);
		postImageStorage = mock(PostImageStorage.class);
		chatService = new ChatService(chatRoomRepository, postRepository, userRepository, postImageStorage);

		// 채팅방 상세 조회와 생성 테스트에서 공통으로 사용할 기본 데이터를 준비한다.
		buyer = user(1L, "buyer", "buyer@example.com", "buyer-provider");
		seller = user(2L, "seller", "seller@example.com", "seller-provider");
		stranger = user(3L, "stranger", "stranger@example.com", "stranger-provider");
		post = post(10L, seller);
	}

	@Test
	@DisplayName("채팅방 생성은 게시글 구매자 판매자로 새 채팅방을 저장한다")
	void createChatRoomCreatesNewRoom() {
		// 게시글과 구매자가 존재하고 기존 채팅방이 없을 때 새 채팅방이 저장되도록 설정한다.
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
		assertThat(response.postTitle()).isEqualTo("키링 판매");
		assertThat(response.buyerId()).isEqualTo(1L);
		assertThat(response.sellerId()).isEqualTo(2L);
	}

	@Test
	@DisplayName("채팅방 생성은 이미 같은 조합의 방이 있으면 기존 채팅방을 반환한다")
	void createChatRoomReturnsExistingRoom() {
		// 같은 게시글, 구매자, 판매자 조합의 채팅방이 이미 있으면 저장하지 않고 기존 방을 반환한다.
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
		// 삭제되지 않은 게시글을 찾지 못하면 판매글 not found 예외가 발생해야 한다.
		when(postRepository.findByIdAndDeletedAtIsNull(10L)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> chatService.createChatRoom(1L, new CreateChatRoomRequest(10L)))
			.isInstanceOf(BusinessException.class)
			.extracting("errorCode")
			.isEqualTo(ErrorCode.POST_NOT_FOUND);
	}

	@Test
	@DisplayName("채팅방 생성은 인증 사용자가 없으면 예외를 던진다")
	void createChatRoomWithoutBuyerThrowsException() {
		// 인증 주체에 해당하는 사용자가 없으면 인증 토큰 예외가 발생해야 한다.
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
		// 판매자가 자신의 게시글에 문의하는 흐름은 명시적으로 차단한다.
		User sameUser = user(2L, "seller", "seller@example.com", "seller-provider");
		when(postRepository.findByIdAndDeletedAtIsNull(10L)).thenReturn(Optional.of(post));
		when(userRepository.findById(2L)).thenReturn(Optional.of(sameUser));

		assertThatThrownBy(() -> chatService.createChatRoom(2L, new CreateChatRoomRequest(10L)))
			.isInstanceOf(BusinessException.class)
			.extracting("errorCode")
			.isEqualTo(ErrorCode.CHAT_ROOM_SELF_NOT_ALLOWED);
	}

	@Test
	@DisplayName("채팅방 목록 조회는 현재 사용자의 상대방 정보를 반환한다")
	void getMyChatRoomsMapsOpponentNickname() {
		// 현재 사용자가 buyer, seller 양쪽 역할로 참여한 채팅방을 준비한다.
		User otherBuyer = user(4L, "another-buyer", "another-buyer@example.com", "another-buyer-provider");
		User otherSeller = user(5L, "another-seller", "another-seller@example.com", "another-seller-provider");

		Post buyerPost = post(11L, otherSeller);
		Post sellerPost = post(12L, buyer);

		ChatRoom buyerChatRoom = chatRoom(101L, buyerPost, buyer, otherSeller);
		buyerChatRoom.updateLastMessage("판매 중인가요?", LocalDateTime.of(2026, 5, 24, 10, 5));

		ChatRoom sellerChatRoom = chatRoom(102L, sellerPost, otherBuyer, buyer);
		sellerChatRoom.updateLastMessage("네 가능합니다.", LocalDateTime.of(2026, 5, 24, 9, 30));

		// 저장소는 이미 정렬된 목록을 반환한다고 가정하고 서비스의 상대방 매핑만 검증한다.
		when(chatRoomRepository.findMyChatRooms(1L)).thenReturn(List.of(buyerChatRoom, sellerChatRoom));

		List<ChatRoomListResponse> responses = chatService.getMyChatRooms(1L);

		assertThat(responses).hasSize(2);
		assertThat(responses.get(0).chatRoomId()).isEqualTo(101L);
		assertThat(responses.get(0).opponentId()).isEqualTo(5L);
		assertThat(responses.get(0).opponentNickname()).isEqualTo("another-seller");
		assertThat(responses.get(0).lastMessageContent()).isEqualTo("판매 중인가요?");
		assertThat(responses.get(1).chatRoomId()).isEqualTo(102L);
		assertThat(responses.get(1).opponentId()).isEqualTo(4L);
		assertThat(responses.get(1).opponentNickname()).isEqualTo("another-buyer");
		assertThat(responses.get(1).lastMessageContent()).isEqualTo("네 가능합니다.");
		verifyNoInteractions(postImageStorage);
	}

	@Test
	@DisplayName("구매자는 채팅방 상세 정보를 조회할 수 있다")
	void 구매자는_채팅방_상세정보를_조회할_수_있다() {
		// 구매자가 참여 중인 채팅방과 대표 이미지를 준비한다.
		post.addImage("posts/10/thumbnail.png", 0);
		ChatRoom chatRoom = chatRoom(100L, post, buyer, seller);

		when(chatRoomRepository.findChatRoomDetailById(100L)).thenReturn(Optional.of(chatRoom));
		when(postImageStorage.imageUrl("posts/10/thumbnail.png"))
			.thenReturn("https://cdn.test/posts/10/thumbnail.png");

		ChatRoomDetailResponse response = chatService.getChatRoom(1L, 100L);

		assertThat(response.chatRoomId()).isEqualTo(100L);
		assertThat(response.postId()).isEqualTo(10L);
		assertThat(response.postTitle()).isEqualTo("키링 판매");
		assertThat(response.postThumbnailImageUrl()).isEqualTo("https://cdn.test/posts/10/thumbnail.png");
		verify(postImageStorage).imageUrl("posts/10/thumbnail.png");
	}

	@Test
	@DisplayName("판매자는 채팅방 상세 정보를 조회할 수 있다")
	void 판매자는_채팅방_상세정보를_조회할_수_있다() {
		// 판매자가 조회해도 동일한 채팅방 상단 정보를 응답해야 한다.
		post.addImage("posts/10/thumbnail.png", 0);
		ChatRoom chatRoom = chatRoom(100L, post, buyer, seller);

		when(chatRoomRepository.findChatRoomDetailById(100L)).thenReturn(Optional.of(chatRoom));
		when(postImageStorage.imageUrl("posts/10/thumbnail.png"))
			.thenReturn("https://cdn.test/posts/10/thumbnail.png");

		ChatRoomDetailResponse response = chatService.getChatRoom(2L, 100L);

		assertThat(response.chatRoomId()).isEqualTo(100L);
		assertThat(response.postId()).isEqualTo(10L);
		assertThat(response.postTitle()).isEqualTo("키링 판매");
		assertThat(response.postThumbnailImageUrl()).isEqualTo("https://cdn.test/posts/10/thumbnail.png");
		verify(postImageStorage).imageUrl("posts/10/thumbnail.png");
	}

	@Test
	@DisplayName("채팅방 참여자가 아니면 상세 조회에 실패한다")
	void 채팅방_참여자가_아니면_상세조회에_실패한다() {
		// 참여하지 않은 사용자는 권한 예외를 받아야 한다.
		post.addImage("posts/10/thumbnail.png", 0);
		ChatRoom chatRoom = chatRoom(100L, post, buyer, seller);

		when(chatRoomRepository.findChatRoomDetailById(100L)).thenReturn(Optional.of(chatRoom));

		assertThatThrownBy(() -> chatService.getChatRoom(stranger.getId(), 100L))
			.isInstanceOf(BusinessException.class)
			.extracting("errorCode")
			.isEqualTo(ErrorCode.CHAT_ROOM_ACCESS_DENIED);
		verify(postImageStorage, never()).imageUrl(any());
	}

	@Test
	@DisplayName("존재하지 않는 채팅방이면 상세 조회에 실패한다")
	void 존재하지_않는_채팅방이면_상세조회에_실패한다() {
		// 채팅방이 없으면 곧바로 not found 예외를 반환해야 한다.
		when(chatRoomRepository.findChatRoomDetailById(999L)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> chatService.getChatRoom(1L, 999L))
			.isInstanceOf(BusinessException.class)
			.extracting("errorCode")
			.isEqualTo(ErrorCode.CHAT_ROOM_NOT_FOUND);
		verify(postImageStorage, never()).imageUrl(any());
	}

	@Test
	@DisplayName("판매글 대표 이미지 URL을 응답한다")
	void 판매글_대표이미지_URL을_응답한다() {
		// objectKey를 스토리지 URL로 변환한 값이 응답에 포함되는지 확인한다.
		Post imagePost = post(11L, seller);
		imagePost.addImage("posts/1/test-image.jpg", 0);
		ChatRoom chatRoom = chatRoom(101L, imagePost, buyer, seller);

		when(chatRoomRepository.findChatRoomDetailById(101L)).thenReturn(Optional.of(chatRoom));
		when(postImageStorage.imageUrl("posts/1/test-image.jpg"))
			.thenReturn("https://cdn.test/posts/1/test-image.jpg");

		ChatRoomDetailResponse response = chatService.getChatRoom(1L, 101L);

		assertThat(response.postThumbnailImageUrl()).isEqualTo("https://cdn.test/posts/1/test-image.jpg");
		verify(postImageStorage).imageUrl("posts/1/test-image.jpg");
	}

	private User user(Long id, String nickname, String email, String providerUserId) {
		// 현재 엔티티 생성 방식과 동일하게 builder를 사용해 사용자를 만든다.
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
		// 판매글 생성도 실제 서비스 코드와 같은 builder 패턴을 사용한다.
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

	private ChatRoom chatRoom(Long id, Post post, User buyer, User seller) {
		// 채팅방 생성은 도메인 정적 팩토리를 사용해 실제 생성 흐름을 맞춘다.
		ChatRoom chatRoom = ChatRoom.create(post, buyer, seller);
		ReflectionTestUtils.setField(chatRoom, "id", id);
		return chatRoom;
	}
}
