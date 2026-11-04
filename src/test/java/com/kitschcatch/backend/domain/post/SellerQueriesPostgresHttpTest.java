// 전용 PostgreSQL DB에서 판매자 상품·채팅 조회 HTTP 계약을 검증한다.
package com.kitschcatch.backend.domain.post;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mockingDetails;
import static java.util.concurrent.TimeUnit.SECONDS;

import com.kitschcatch.backend.domain.chat.repository.ChatMessageRepository;
import com.kitschcatch.backend.domain.post.entity.ProductStatus;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
@EnabledIfEnvironmentVariable(named="ISSUE48_TEST_DB_URL", matches="jdbc:postgresql:.*")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.datasource.driver-class-name=org.postgresql.Driver", "spring.jpa.hibernate.ddl-auto=create-drop",
    "kakao.oauth.native-app-key=test-native-app-key", "app.orders.expiration-enabled=false",
    "app.payments.recovery.enabled=false", "app.s3.public-base-url=https://cdn.example.test", "app.s3.download-url-ttl=15m",
    "app.s3.bucket=test-bucket", "spring.jpa.properties.hibernate.generate_statistics=true"
})
class SellerQueriesPostgresHttpTest extends SellerQueriesHttpContract {
    @MockitoSpyBean
    com.kitschcatch.backend.domain.post.repository.PostFavoriteRepository favorites;

    @MockitoSpyBean
    ChatMessageRepository messageRepository;

    @Test
    void concurrentReadCommitDoesNotMixListSnapshotAndUnreadCounts() {
        var room = room(post(seller, ProductStatus.ON_SALE), buyer, seller);
        message(room, buyer, "TEXT", false);
        var once = new AtomicBoolean();
        doAnswer(invocation -> {
            if (once.compareAndSet(false, true)) {
                CompletableFuture.runAsync(() ->
                    jdbc.update("UPDATE chat_messages SET is_read=true WHERE chat_room_id=?", room.getId())).get(10, SECONDS);
            }
            return mockingDetails(messageRepository).getMockCreationSettings()
                .getDefaultAnswer().answer(invocation);
        }).when(messageRepository).countUnreadByRoomIds(anyList(), eq(seller.getId()));
        try {
            assertThat(unread(sellerToken)).containsEntry(room.getId(), 1L);
            assertThat(unread(sellerToken)).containsEntry(room.getId(), 0L);
        } finally {
            org.mockito.Mockito.reset(messageRepository);
        }
    }

    @Test
    void messageArrivingDuringReadRemainsUnread() {
        var room = room(post(seller, ProductStatus.ON_SALE), buyer, seller);
        message(room, buyer, "TEXT", false);
        doAnswer(invocation -> {
            var ids = mockingDetails(messageRepository).getMockCreationSettings().getDefaultAnswer().answer(invocation);
            CompletableFuture.runAsync(() -> message(room, buyer, "IMAGE", false)).get(10, SECONDS);
            return ids;
        }).when(messageRepository).findUnreadMessageIds(room.getId(), seller.getId());
        try {
            var result = chatMessages.markMessagesAsRead(seller.getId(), room.getId());
            assertThat(result.readMessageIds()).hasSize(1);
            assertThat(unread(sellerToken)).containsEntry(room.getId(), 1L);
        } finally {
            org.mockito.Mockito.reset(messageRepository);
        }
    }

    @Test
    void favoritePageAndBothCountsUseOneSnapshotDuringConcurrentChanges() {
        var item = post(seller, ProductStatus.ON_SALE);
        jdbc.update("INSERT INTO post_favorites(user_id,post_id,created_at) VALUES (?,?,?)",
            buyer.getId(), item.getId(), java.time.LocalDateTime.now());
        var once = new AtomicBoolean();
        doAnswer(invocation -> {
            if (once.compareAndSet(false, true)) {
                CompletableFuture.runAsync(() -> {
                    jdbc.update("DELETE FROM post_favorites WHERE post_id=?", item.getId());
                    room(item, outsider, seller);
                }).get(10, SECONDS);
            }
            return mockingDetails(favorites).getMockCreationSettings().getDefaultAnswer().answer(invocation);
        }).when(favorites).countByPostIds(anyList());
        try {
            var page = get("/api/users/me/favorite-posts", buyerToken);
            assertThat(page.rows()).hasSize(1);
            assertCounts(page.rows().getFirst(), 1, 0);
            assertThat(get("/api/users/me/favorite-posts", buyerToken).rows()).isEmpty();
            assertCounts(get("/api/posts/" + item.getId(), buyerToken).data(), 0, 1);
        } finally {
            org.mockito.Mockito.reset(favorites);
        }
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("ISSUE48_TEST_DB_URL"));
        registry.add("spring.datasource.username", () -> System.getenv().getOrDefault("ISSUE48_TEST_DB_USERNAME", "postgres"));
        registry.add("spring.datasource.password", () -> System.getenv().getOrDefault("ISSUE48_TEST_DB_PASSWORD", ""));
    }
}
