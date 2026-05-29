package com.kitschcatch.backend.domain.chat.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.kitschcatch.backend.domain.chat.entity.ChatMessage;
import com.kitschcatch.backend.domain.chat.entity.ChatRoom;
import com.kitschcatch.backend.domain.chat.entity.MessageType;
import com.kitschcatch.backend.domain.post.entity.Post;
import com.kitschcatch.backend.domain.post.entity.ProductCategory;
import com.kitschcatch.backend.domain.post.entity.ProductCondition;
import com.kitschcatch.backend.domain.post.entity.ProductStatus;
import com.kitschcatch.backend.domain.user.entity.AuthProvider;
import com.kitschcatch.backend.domain.user.entity.User;
import com.kitschcatch.backend.domain.user.repository.UserRepository;
import jakarta.persistence.EntityManager;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;

@DataJpaTest(properties = {
	"spring.datasource.url=jdbc:h2:mem:chat-message-repository;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE",
	"spring.datasource.driver-class-name=org.h2.Driver",
	"spring.datasource.username=sa",
	"spring.datasource.password=",
	"spring.jpa.hibernate.ddl-auto=create-drop"
})
class ChatMessageRepositoryTest {

	@Autowired
	private ChatMessageRepository chatMessageRepository;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private EntityManager entityManager;

	@Test
	@DisplayName("상대방이 보낸 안 읽은 메시지 ID만 조회한다")
	void findUnreadMessageIdsReturnsOnlyOpponentUnreadMessages() {
		User currentUser = saveUser("current-user", "current@example.com", "current-provider");
		User opponent = saveUser("opponent", "opponent@example.com", "opponent-provider");
		Post post = savePost(opponent, "읽음 처리 테스트 게시글");
		ChatRoom chatRoom = saveChatRoom(post, currentUser, opponent);

		ChatMessage message1 = saveMessage(chatRoom, opponent, "상대방 안 읽음 1", false);
		ChatMessage message2 = saveMessage(chatRoom, currentUser, "내가 보낸 메시지", false);
		ChatMessage message3 = saveMessage(chatRoom, opponent, "이미 읽은 메시지", true);
		ChatMessage message4 = saveMessage(chatRoom, opponent, "상대방 안 읽음 2", false);

		entityManager.flush();
		updateCreatedAt(message1.getId(), LocalDateTime.of(2026, 5, 29, 10, 0));
		updateCreatedAt(message2.getId(), LocalDateTime.of(2026, 5, 29, 10, 1));
		updateCreatedAt(message3.getId(), LocalDateTime.of(2026, 5, 29, 10, 2));
		updateCreatedAt(message4.getId(), LocalDateTime.of(2026, 5, 29, 10, 3));
		entityManager.clear();

		List<Long> result = chatMessageRepository.findUnreadMessageIds(chatRoom.getId(), currentUser.getId());

		assertThat(result).containsExactly(message1.getId(), message4.getId());
		assertThat(result).doesNotContain(message2.getId(), message3.getId());
	}

	@Test
	@DisplayName("메시지 ID 목록을 받아 읽음 처리한다")
	void markAsReadByIdsUpdatesMessages() {
		User currentUser = saveUser("current-user", "current2@example.com", "current-provider-2");
		User opponent = saveUser("opponent", "opponent2@example.com", "opponent-provider-2");
		Post post = savePost(opponent, "읽음 처리 업데이트 게시글");
		ChatRoom chatRoom = saveChatRoom(post, currentUser, opponent);

		ChatMessage message1 = saveMessage(chatRoom, opponent, "첫 번째 안 읽음", false);
		ChatMessage message2 = saveMessage(chatRoom, opponent, "두 번째 안 읽음", false);

		entityManager.flush();
		entityManager.clear();

		int updatedCount = chatMessageRepository.markAsReadByIds(List.of(message1.getId(), message2.getId()));

		entityManager.flush();
		entityManager.clear();

		ChatMessage updatedMessage1 = chatMessageRepository.findById(message1.getId()).orElseThrow();
		ChatMessage updatedMessage2 = chatMessageRepository.findById(message2.getId()).orElseThrow();

		assertThat(updatedCount).isEqualTo(2);
		assertThat(updatedMessage1.isRead()).isTrue();
		assertThat(updatedMessage2.isRead()).isTrue();
	}

	private User saveUser(String nickname, String email, String providerUserId) {
		return userRepository.save(User.builder()
			.nickname(nickname)
			.email(email)
			.authProvider(AuthProvider.KAKAO)
			.providerUserId(providerUserId)
			.build());
	}

	private Post savePost(User seller, String title) {
		Post post = Post.builder()
			.user(seller)
			.title(title)
			.description("채팅 메시지 테스트용 게시글입니다.")
			.price(12000L)
			.productCategory(ProductCategory.GOODS)
			.productCondition(ProductCondition.NEW)
			.productStatus(ProductStatus.ON_SALE)
			.build();
		entityManager.persist(post);
		return post;
	}

	private ChatRoom saveChatRoom(Post post, User buyer, User seller) {
		ChatRoom chatRoom = ChatRoom.create(post, buyer, seller);
		entityManager.persist(chatRoom);
		return chatRoom;
	}

	private ChatMessage saveMessage(ChatRoom chatRoom, User sender, String content, boolean isRead) {
		ChatMessage chatMessage = ChatMessage.builder()
			.chatRoom(chatRoom)
			.sender(sender)
			.messageType(MessageType.TEXT)
			.content(content)
			.imageUrl(null)
			.isRead(isRead)
			.build();
		entityManager.persist(chatMessage);
		return chatMessage;
	}

	private void updateCreatedAt(Long messageId, LocalDateTime createdAt) {
		entityManager.createNativeQuery("""
			update chat_messages
			set created_at = :createdAt
			where id = :messageId
			""")
			.setParameter("createdAt", Timestamp.valueOf(createdAt))
			.setParameter("messageId", messageId)
			.executeUpdate();
	}
}
