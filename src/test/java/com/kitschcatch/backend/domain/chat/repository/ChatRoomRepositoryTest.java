package com.kitschcatch.backend.domain.chat.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.kitschcatch.backend.domain.chat.entity.ChatRoom;
import com.kitschcatch.backend.domain.post.entity.Post;
import com.kitschcatch.backend.domain.post.entity.ProductCategory;
import com.kitschcatch.backend.domain.post.entity.ProductCondition;
import com.kitschcatch.backend.domain.post.entity.ProductStatus;
import com.kitschcatch.backend.domain.user.entity.AuthProvider;
import com.kitschcatch.backend.domain.user.entity.User;
import com.kitschcatch.backend.domain.user.repository.UserRepository;
import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import java.util.List;
import org.hibernate.Hibernate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;

@DataJpaTest(properties = {
	"spring.datasource.url=jdbc:h2:mem:chat-room-repository;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE",
	"spring.datasource.driver-class-name=org.h2.Driver",
	"spring.datasource.username=sa",
	"spring.datasource.password=",
	"spring.jpa.hibernate.ddl-auto=create-drop"
})
class ChatRoomRepositoryTest {

	@Autowired
	private ChatRoomRepository chatRoomRepository;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private EntityManager entityManager;

	@Test
	@DisplayName("현재 사용자가 참여한 채팅방만 마지막 메시지 시간 기준 내림차순으로 조회한다")
	void findMyChatRoomsReturnsParticipatingRoomsInDescendingLastMessageOrder() {
		// 현재 로그인 사용자가 buyer, seller 양쪽으로 참여한 데이터를 준비한다.
		User currentUser = saveUser("current-user", "current@example.com", "current-provider");
		User buyerOpponent = saveUser("buyer-opponent", "buyer-opponent@example.com", "buyer-opponent-provider");
		User sellerOpponent = saveUser("seller-opponent", "seller-opponent@example.com", "seller-opponent-provider");
		User outsiderBuyer = saveUser("outsider-buyer", "outsider-buyer@example.com", "outsider-buyer-provider");
		User outsiderSeller = saveUser("outsider-seller", "outsider-seller@example.com", "outsider-seller-provider");

		Post buyerPost = savePost(sellerOpponent, "구매자 방 게시글", "posts/11/thumbnail.png");
		Post sellerPost = savePost(currentUser, "판매자 방 게시글", "posts/12/thumbnail.png");
		Post outsiderPost = savePost(outsiderSeller, "외부 방 게시글", "posts/13/thumbnail.png");

		ChatRoom buyerChatRoom = saveChatRoom(
			buyerPost,
			currentUser,
			sellerOpponent,
			"buyer 최신 메시지",
			LocalDateTime.of(2026, 5, 27, 10, 0)
		);
		ChatRoom sellerChatRoom = saveChatRoom(
			sellerPost,
			buyerOpponent,
			currentUser,
			"seller 이전 메시지",
			LocalDateTime.of(2026, 5, 27, 9, 0)
		);
		saveChatRoom(
			outsiderPost,
			outsiderBuyer,
			outsiderSeller,
			"외부 사용자 메시지",
			LocalDateTime.of(2026, 5, 27, 11, 0)
		);

		entityManager.flush();
		entityManager.clear();

		List<ChatRoom> chatRooms = chatRoomRepository.findMyChatRooms(currentUser.getId());

		assertThat(chatRooms).hasSize(2);
		assertThat(chatRooms)
			.extracting(ChatRoom::getId)
			.containsExactly(buyerChatRoom.getId(), sellerChatRoom.getId());
		assertThat(chatRooms)
			.extracting(chatRoom -> chatRoom.getBuyer().getId())
			.contains(currentUser.getId(), buyerOpponent.getId())
			.doesNotContain(outsiderBuyer.getId());
		assertThat(chatRooms)
			.extracting(chatRoom -> chatRoom.getSeller().getId())
			.contains(currentUser.getId(), sellerOpponent.getId())
			.doesNotContain(outsiderSeller.getId());
		assertThat(chatRooms)
			.extracting(ChatRoom::getLastMessageAt)
			.containsExactly(
				LocalDateTime.of(2026, 5, 27, 10, 0),
				LocalDateTime.of(2026, 5, 27, 9, 0)
				);
	}

	@Test
	@DisplayName("채팅방 상세 조회 전용 메서드는 게시글 이미지와 참여자를 함께 조회한다")
	void findChatRoomDetailByIdLoadsPostImagesBuyerAndSeller() {
		// 상세 조회 화면에 필요한 buyer, seller, post, post.images 데이터를 저장한다.
		User buyer = saveUser("buyer", "buyer@example.com", "buyer-provider");
		User seller = saveUser("seller", "seller@example.com", "seller-provider");
		Post detailPost = savePost(seller, "상세 조회 게시글", "posts/1/test-image.jpg");
		ChatRoom chatRoom = saveChatRoom(
			detailPost,
			buyer,
			seller,
			"상세 조회용 메시지",
			LocalDateTime.of(2026, 5, 27, 12, 0)
		);

		entityManager.flush();
		entityManager.clear();

		// 상세 조회 전용 쿼리 메서드로 데이터를 다시 읽어온다.
		ChatRoom foundChatRoom = chatRoomRepository.findChatRoomDetailById(chatRoom.getId()).orElseThrow();

		assertThat(Hibernate.isInitialized(foundChatRoom.getPost())).isTrue();
		assertThat(Hibernate.isInitialized(foundChatRoom.getPost().getImages())).isTrue();
		assertThat(Hibernate.isInitialized(foundChatRoom.getBuyer())).isTrue();
		assertThat(Hibernate.isInitialized(foundChatRoom.getSeller())).isTrue();
		assertThat(foundChatRoom.getPost().getId()).isEqualTo(detailPost.getId());
		assertThat(foundChatRoom.getBuyer().getId()).isEqualTo(buyer.getId());
		assertThat(foundChatRoom.getSeller().getId()).isEqualTo(seller.getId());
		assertThat(foundChatRoom.getPost().getImages())
			.extracting(image -> image.getObjectKey())
			.containsExactly("posts/1/test-image.jpg");
	}

	private User saveUser(String nickname, String email, String providerUserId) {
		return userRepository.save(User.builder()
			.nickname(nickname)
			.email(email)
			.authProvider(AuthProvider.KAKAO)
			.providerUserId(providerUserId)
			.build());
	}

	private Post savePost(User seller, String title, String imageObjectKey) {
		Post post = Post.builder()
			.user(seller)
			.title(title)
			.description("테스트용 판매글입니다.")
			.price(12000L)
			.productCategory(ProductCategory.GOODS)
			.productCondition(ProductCondition.NEW)
			.productStatus(ProductStatus.ON_SALE)
			.build();
		post.addImage(imageObjectKey, 0);
		entityManager.persist(post);
		return post;
	}

	private ChatRoom saveChatRoom(
		Post post,
		User buyer,
		User seller,
		String lastMessageContent,
		LocalDateTime lastMessageAt
	) {
		ChatRoom chatRoom = ChatRoom.create(post, buyer, seller);
		chatRoom.updateLastMessage(lastMessageContent, lastMessageAt);
		entityManager.persist(chatRoom);
		return chatRoom;
	}
}
