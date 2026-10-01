// 실제 HTTP·JWT·DB로 판매자 목록과 채팅 필터의 권한·페이지·SQL 횟수를 검증한다.
package com.kitschcatch.backend.domain.post;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;

import com.kitschcatch.backend.domain.chat.entity.ChatRoom;
import com.kitschcatch.backend.domain.chat.repository.ChatRoomRepository;
import com.kitschcatch.backend.domain.order.toss.TossPaymentsClient;
import com.kitschcatch.backend.domain.post.entity.*;
import com.kitschcatch.backend.domain.post.repository.PostRepository;
import com.kitschcatch.backend.domain.user.entity.*;
import com.kitschcatch.backend.domain.user.repository.UserRepository;
import com.kitschcatch.backend.global.security.JwtTokenProvider;
import jakarta.persistence.EntityManagerFactory;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.client.RestClient;
import software.amazon.awssdk.services.s3.S3Client;

abstract class SellerQueriesHttpContract {
    @LocalServerPort int port;
    @Autowired UserRepository users;
    @Autowired PostRepository posts;
    @Autowired ChatRoomRepository rooms;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtTokenProvider tokens;
    @Autowired EntityManagerFactory entityManagerFactory;
    @MockitoBean TossPaymentsClient toss;
    @MockitoBean S3Client s3;
    User seller, buyer, outsider;
    String sellerToken, buyerToken, outsiderToken;

    @BeforeEach
    void reset() {
        for (String table : List.of("chat_messages", "chat_rooms", "payment_webhook_events", "payment_attempts", "payments", "orders", "post_images", "posts", "users")) {
            jdbc.update("DELETE FROM " + table);
        }
        seller = user(); buyer = user(); outsider = user();
        sellerToken = tokens.createAccessToken(seller.getId());
        buyerToken = tokens.createAccessToken(buyer.getId());
        outsiderToken = tokens.createAccessToken(outsider.getId());
    }

    @Test
    void sellerListsExposeEveryStatusExcludeDeletedAndStayScoped() {
        for (ProductStatus status : ProductStatus.values()) post(seller, status);
        Post deleted = post(seller, ProductStatus.ON_SALE); deleted.delete(); posts.save(deleted);
        post(buyer, ProductStatus.ON_SALE);
        String otherPath = "/api/users/" + seller.getId() + "/posts";
        for (String path : List.of("/api/users/me/posts", otherPath)) {
            var all = get(path, sellerToken);
            assertThat(all.status()).isEqualTo(200);
            assertThat(all.rows()).hasSize(3);
            assertThat(all.rows()).extracting(row -> row.get("productStatus"))
                .containsExactly("SOLD_OUT", "RESERVED", "ON_SALE");
            for (ProductStatus status : ProductStatus.values()) {
                assertThat(get(path + "?status=" + status, sellerToken).rows())
                    .extracting(row -> row.get("productStatus")).containsExactly(status.name());
            }
        }
        assertThat(get(otherPath, buyerToken).rows()).hasSize(3);
        assertThat(get("/api/users/me/posts?userId=" + seller.getId(), buyerToken).rows()).hasSize(1);
        assertThat(get("/api/users/me/posts", outsiderToken).rows()).isEmpty();
        assertThat(get("/api/users/9223372036854775807/posts", sellerToken).status()).isEqualTo(404);
        verifyNoInteractions(s3, toss);
    }

    @Test
    void pagesHaveStableTieOrderAndTotalsIncludingEmptyAndLastPage() {
        Post first = post(seller, ProductStatus.ON_SALE), second = post(seller, ProductStatus.ON_SALE);
        jdbc.update("UPDATE posts SET created_at=? WHERE user_id=?", LocalDateTime.of(2026, 1, 1, 0, 0), seller.getId());
        var page = get("/api/users/me/posts?size=1", sellerToken);
        assertThat(page.ids("id")).containsExactly(second.getId());
        assertThat(page.data()).containsEntry("page", 0).containsEntry("size", 1).containsEntry("totalElements", 2).containsEntry("totalPages", 2);
        assertThat(get("/api/users/me/posts?page=1&size=1", sellerToken).ids("id")).containsExactly(first.getId());
        var out = get("/api/users/me/posts?page=10000&size=100", sellerToken);
        assertThat(out.rows()).isEmpty(); assertThat(out.data()).containsEntry("totalElements", 2);
        var empty = get("/api/users/me/posts", outsiderToken);
        assertThat(empty.data()).containsEntry("totalElements", 0).containsEntry("totalPages", 0);
    }

    @ParameterizedTest
    @ValueSource(strings = {"page=-1", "page=10001", "size=0", "size=101", "size=2147483647", "status=PAID", "status=", "page=x", "status=on_sale"})
    void malformedSellerQueriesReturn400(String query) {
        assertThat(get("/api/users/me/posts?" + query, sellerToken).status()).isEqualTo(400);
        assertThat(get("/api/users/" + seller.getId() + "/posts?" + query, buyerToken).status()).isEqualTo(400);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "abc", "9223372036854775808"})
    void malformedSellerIdsReturn400(String id) {
        assertThat(get("/api/users/" + id + "/posts", sellerToken).status()).isEqualTo(400);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/users/me/posts", "/api/users/1/posts", "/api/chat-rooms?role=SELLER&postId=1"})
    void authenticationCannotBeInjectedOrReplacedByInvalidOrRefreshToken(String path) {
        for (String token : new String[]{null, "invalid", tokens.createRefreshToken(seller.getId()), tokens.createAccessToken(Long.MAX_VALUE)}) {
            assertThat(get(path, token).status()).isEqualTo(401);
        }
    }

    @Test
    void chatFiltersNeverEscapeParticipationAndDeletionConditions() {
        Post sale = post(seller, ProductStatus.ON_SALE), purchase = post(buyer, ProductStatus.ON_SALE);
        ChatRoom selling = room(sale, buyer, seller), buying = room(purchase, seller, buyer);
        room(sale, outsider, seller);
        Post foreign = post(outsider, ProductStatus.ON_SALE); room(foreign, buyer, outsider);
        assertThat(get("/api/chat-rooms", sellerToken).chatRows()).hasSize(3);
        assertThat(get("/api/chat-rooms?role=BUYER", sellerToken).ids("chatRoomId")).containsExactly(buying.getId());
        assertThat(get("/api/chat-rooms?role=SELLER&postId=" + sale.getId(), sellerToken).chatRows()).hasSize(2);
        var scoped = get("/api/chat-rooms?postId=" + sale.getId(), buyerToken);
        assertThat(scoped.ids("chatRoomId")).containsExactly(selling.getId());
        assertThat(scoped.chatRows().getFirst()).containsEntry("postTitle", sale.getTitle());
        assertThat(((Number)scoped.chatRows().getFirst().get("postId")).longValue()).isEqualTo(sale.getId());
        assertThat(get("/api/chat-rooms?role=SELLER&postId=" + sale.getId(), buyerToken).chatRows()).isEmpty();
        assertThat(get("/api/chat-rooms?role=SELLER&postId=" + foreign.getId() + "&userId=" + outsider.getId(), sellerToken).chatRows()).isEmpty();
        assertThat(get("/api/chat-rooms?postId=9223372036854775807", sellerToken).chatRows()).isEmpty();
        jdbc.update("UPDATE chat_rooms SET seller_deleted_at=? WHERE id=?", LocalDateTime.now(), selling.getId());
        assertThat(get("/api/chat-rooms?role=SELLER&postId=" + sale.getId(), sellerToken).ids("chatRoomId")).doesNotContain(selling.getId());
        assertThat(get("/api/chat-rooms?postId=" + sale.getId(), buyerToken).ids("chatRoomId")).containsExactly(selling.getId());
        jdbc.update("UPDATE chat_rooms SET buyer_deleted_at=? WHERE id=?", LocalDateTime.now(), selling.getId());
        assertThat(get("/api/chat-rooms?postId=" + sale.getId(), buyerToken).chatRows()).isEmpty();
        sale.delete(); posts.save(sale);
        assertThat(get("/api/chat-rooms?role=SELLER&postId=" + sale.getId(), sellerToken).chatRows()).hasSize(1);
        verifyNoInteractions(s3, toss);
    }

    @ParameterizedTest
    @ValueSource(strings = {"role=ADMIN", "role=", "role=seller", "postId=", "postId=0", "postId=-1", "postId=x", "postId=9223372036854775808"})
    void malformedChatFiltersReturn400(String query) {
        assertThat(get("/api/chat-rooms?" + query, sellerToken).status()).isEqualTo(400);
    }

    @Test
    void chatTieOrderAndBoundedSqlCountsDoNotGrowPerResult() {
        for (int i=0; i<25; i++) room(post(seller, ProductStatus.ON_SALE), buyer, seller);
        jdbc.update("UPDATE chat_rooms SET last_message_at=?", LocalDateTime.of(2026,1,1,0,0));
        var stats = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        stats.clear();
        var chats = get("/api/chat-rooms?role=SELLER", sellerToken);
        assertThat(chats.chatRows()).hasSize(25);
        assertThat(chats.ids("chatRoomId")).isSortedAccordingTo(java.util.Comparator.reverseOrder());
        assertThat(stats.getPrepareStatementCount()).isLessThanOrEqualTo(3);
        stats.clear();
        var page = get("/api/users/me/posts?size=100", sellerToken);
        assertThat(page.rows()).hasSize(25);
        assertThat(stats.getPrepareStatementCount()).isLessThanOrEqualTo(5);
        assertThat(((List<?>) page.rows().getFirst().get("images"))).hasSize(1);
        verifyNoInteractions(s3, toss);
    }

    User user() {
        String id = UUID.randomUUID().toString();
        return users.save(User.builder().nickname(id.substring(0,8)).email(id+"@test.invalid")
            .authProvider(AuthProvider.KAKAO).providerUserId(id).build());
    }
    Post post(User owner, ProductStatus status) {
        Post post = Post.builder().user(owner).title("상품").description("설명").price(12000L)
            .productCategory(ProductCategory.GOODS).productCondition(ProductCondition.NEW).productStatus(status).build();
        post.addImage("posts/"+owner.getId()+"/image.jpg",0);
        return posts.save(post);
    }
    ChatRoom room(Post post, User buyer, User seller) { return rooms.save(ChatRoom.create(post,buyer,seller)); }
    Response get(String path, String token) {
        var request = RestClient.create("http://localhost:"+port).get().uri(path);
        if (token != null) request.header("Authorization", "Bearer "+token);
        return request.exchange((req,res) -> new Response(res.getStatusCode().value(), res.bodyTo(Map.class)));
    }
    record Response(int status, Map<String,Object> body) {
        @SuppressWarnings("unchecked") Map<String,Object> data() { return (Map<String,Object>)body.get("data"); }
        @SuppressWarnings("unchecked") List<Map<String,Object>> rows() { return (List<Map<String,Object>>)data().get("content"); }
        @SuppressWarnings("unchecked") List<Map<String,Object>> chatRows() { return (List<Map<String,Object>>)body.get("data"); }
        List<Long> ids(String key) {
            return (key.equals("id") ? rows() : chatRows()).stream().map(row -> ((Number)row.get(key)).longValue()).toList();
        }
    }
}
