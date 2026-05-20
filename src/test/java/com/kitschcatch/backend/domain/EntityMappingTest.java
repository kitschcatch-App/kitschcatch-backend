package com.kitschcatch.backend.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.kitschcatch.backend.domain.chat.entity.ChatMessage;
import com.kitschcatch.backend.domain.chat.entity.ChatRoom;
import com.kitschcatch.backend.domain.chat.entity.MessageType;
import com.kitschcatch.backend.domain.auth.entity.RefreshToken;
import com.kitschcatch.backend.domain.order.entity.OrderStatus;
import com.kitschcatch.backend.domain.order.entity.PgProvider;
import com.kitschcatch.backend.domain.order.entity.PurchaseOrder;
import com.kitschcatch.backend.domain.post.entity.Post;
import com.kitschcatch.backend.domain.post.entity.PostImage;
import com.kitschcatch.backend.domain.post.entity.ProductCategory;
import com.kitschcatch.backend.domain.post.entity.ProductCondition;
import com.kitschcatch.backend.domain.post.entity.ProductStatus;
import com.kitschcatch.backend.domain.user.entity.AuthProvider;
import com.kitschcatch.backend.domain.user.entity.User;
import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;

@DataJpaTest(properties = {
	"spring.datasource.url=jdbc:h2:mem:entity-mapping;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE",
	"spring.datasource.driver-class-name=org.h2.Driver",
	"spring.datasource.username=sa",
	"spring.datasource.password=",
	"spring.jpa.hibernate.ddl-auto=create-drop"
})
class EntityMappingTest {

	@Autowired
	private EntityManager entityManager;

	@Test
	@DisplayName("ERD의 사용자, 게시글, 주문, 채팅방, 채팅 메시지를 JPA 엔티티로 저장한다")
	void persistEntitiesFromErd() {
		User seller = User.builder()
			.nickname("seller")
			.email("seller@example.com")
			.authProvider(AuthProvider.KAKAO)
			.providerUserId("kakao-seller")
			.build();
		User buyer = User.builder()
			.nickname("buyer")
			.email("buyer@example.com")
			.authProvider(AuthProvider.KAKAO)
			.providerUserId("kakao-buyer")
			.build();
		entityManager.persist(seller);
		entityManager.persist(buyer);

		Post post = Post.builder()
			.user(seller)
			.title("키링")
			.description("미개봉 상품")
			.price(12000L)
			.productCategory(ProductCategory.GOODS)
			.productCondition(ProductCondition.NEW)
			.productStatus(ProductStatus.ON_SALE)
			.build();
		post.addImage("posts/1/image.png", 0);
		entityManager.persist(post);

		PurchaseOrder order = PurchaseOrder.builder()
			.user(buyer)
			.post(post)
			.amount(12000L)
			.pgProvider(PgProvider.TOSS_PAYMENTS)
			.pgPaymentKey("payment-key")
			.pgTransactionId("transaction-id")
			.orderStatus(OrderStatus.PAID)
			.build();
		entityManager.persist(order);

		ChatRoom chatRoom = ChatRoom.builder()
			.post(post)
			.buyer(buyer)
			.seller(seller)
			.lastMessageContent("구매 가능할까요?")
			.build();
		entityManager.persist(chatRoom);

		ChatMessage chatMessage = ChatMessage.builder()
			.chatRoom(chatRoom)
			.sender(buyer)
			.messageType(MessageType.TEXT)
			.content("구매 가능할까요?")
			.isRead(false)
			.build();
		entityManager.persist(chatMessage);
		entityManager.flush();
		entityManager.clear();

		ChatMessage savedMessage = entityManager.find(ChatMessage.class, chatMessage.getId());

		assertThat(savedMessage.getChatRoom().getPost().getTitle()).isEqualTo("키링");
		assertThat(savedMessage.getChatRoom().getPost().getImages())
			.extracting(PostImage::getObjectKey)
			.containsExactly("posts/1/image.png");
		assertThat(savedMessage.getChatRoom().getBuyer().getEmail()).isEqualTo("buyer@example.com");
		assertThat(savedMessage.getSender().getNickname()).isEqualTo("buyer");
		assertThat(entityManager.find(PurchaseOrder.class, order.getId()).getOrderStatus()).isEqualTo(OrderStatus.PAID);
	}

	@Test
	@DisplayName("카카오 로그인 사용자의 리프레시 토큰 해시를 저장한다")
	void persistKakaoUserRefreshToken() {
		User user = User.builder()
			.nickname("kakao-user")
			.email("kakao@example.com")
			.authProvider(AuthProvider.KAKAO)
			.providerUserId("123456789")
			.build();
		entityManager.persist(user);

		RefreshToken refreshToken = RefreshToken.builder()
			.user(user)
			.tokenHash("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef")
			.expiresAt(LocalDateTime.now().plusDays(14))
			.build();
		entityManager.persist(refreshToken);
		entityManager.flush();
		entityManager.clear();

		RefreshToken savedToken = entityManager.find(RefreshToken.class, refreshToken.getId());

		assertThat(savedToken.getUser().getEmail()).isEqualTo("kakao@example.com");
		assertThat(savedToken.isRevoked()).isFalse();
	}
}
