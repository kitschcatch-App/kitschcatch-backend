// 실제 HTTP와 로컬 PG 대역으로 후기 권한·상태·동시성·집계 계약을 검증한다.
package com.kitschcatch.backend.domain.review;

import static org.assertj.core.api.Assertions.*;
import com.kitschcatch.backend.domain.post.entity.*;
import com.kitschcatch.backend.domain.post.repository.PostRepository;
import com.kitschcatch.backend.domain.user.entity.*;
import com.kitschcatch.backend.domain.user.repository.UserRepository;
import com.kitschcatch.backend.global.security.JwtTokenProvider;
import com.sun.net.httpserver.HttpServer;
import jakarta.persistence.EntityManagerFactory;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.datasource.url=jdbc:h2:mem:issue56-review;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password=",
    "spring.jpa.hibernate.ddl-auto=create-drop", "kakao.oauth.native-app-key=test",
    "app.orders.expiration-enabled=false", "app.payments.recovery.enabled=false",
    "spring.jpa.properties.hibernate.generate_statistics=true"
})
class TransactionReviewHttpTest extends TransactionReviewHttpContract {}

abstract class TransactionReviewHttpContract {
    @LocalServerPort int port;
    @Autowired UserRepository users;
    @Autowired PostRepository posts;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtTokenProvider tokens;
    @Autowired EntityManagerFactory entityManagerFactory;
    User buyer, seller, stranger;
    String buyerToken, sellerToken, strangerToken;
    static HttpServer pg;
    static volatile String pgBody;
    static final AtomicInteger pgCalls = new AtomicInteger();

    @DynamicPropertySource
    static void pgProperties(DynamicPropertyRegistry registry) throws Exception {
        pg = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        pg.createContext("/v1/payments", exchange -> {
            pgCalls.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            byte[] response = pgBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            try (var output = exchange.getResponseBody()) { output.write(response); }
        });
        pg.start();
        registry.add("app.toss-payments.base-url", () -> "http://127.0.0.1:" + pg.getAddress().getPort());
        registry.add("app.toss-payments.secret-key", () -> "local-issue56-test-only");
    }

    @AfterAll static void stopPg() { if (pg != null) pg.stop(0); }

    @BeforeEach void resetData() {
        for (String table : List.of("transaction_reviews", "settlement_recipients", "payment_webhook_events",
            "payment_attempts", "payments", "orders", "post_images", "posts", "users"))
            jdbc.update("DELETE FROM " + table);
        buyer = user("구매자"); seller = user("판매자"); stranger = user("타인");
        buyerToken = tokens.createAccessToken(buyer.getId());
        sellerToken = tokens.createAccessToken(seller.getId());
        strangerToken = tokens.createAccessToken(stranger.getId());
        pgCalls.set(0);
    }

    User user(String nickname) {
        String id = UUID.randomUUID().toString();
        return users.saveAndFlush(User.builder().nickname(nickname).email(id + "@example.test")
            .authProvider(AuthProvider.KAKAO).providerUserId(id).build());
    }
    OrderFixture order(User purchaser, User owner) {
        var post = posts.saveAndFlush(Post.builder().user(owner).title("상품").description("설명").price(12000L)
            .productCategory(ProductCategory.GOODS).productCondition(ProductCondition.NEW)
            .productStatus(ProductStatus.ON_SALE).build());
        var response = request("POST", "/api/orders", tokens.createAccessToken(purchaser.getId()),
            Map.of("postId", post.getId(), "amount", 12000, "paymentMethod", "CARD"));
        assertThat(response.status()).isEqualTo(201);
        return new OrderFixture((String) response.data().get("orderId"),
            (String) response.data().get("paymentId"), post.getId());
    }
    void pgResult(OrderFixture order, String status) {
        pgBody = """
            {"paymentKey":"pg-%s","orderId":"%s","totalAmount":12000,"status":"%s","balanceAmount":%d}
            """.formatted(order.paymentId(), order.orderId(), status, "CANCELED".equals(status) ? 0 : 12000);
    }
    void approve(OrderFixture order) {
        pgResult(order, "DONE");
        assertThat(request("POST", "/api/payments/" + order.paymentId() + "/confirm", buyerToken,
            Map.of("paymentId", order.paymentId(), "paymentKey", "pg-" + order.paymentId())).status()).isEqualTo(200);
    }
    OrderFixture confirmed(User purchaser, User owner) {
        var order = order(purchaser, owner);
        String previous = buyerToken;
        buyerToken = tokens.createAccessToken(purchaser.getId());
        try {
            approve(order);
            assertThat(request("POST", "/api/orders/" + order.orderId() + "/shipment",
                tokens.createAccessToken(owner.getId()), Map.of("carrierCode", "CJ_LOGISTICS", "trackingNumber", "123456789012")).status()).isEqualTo(200);
            assertThat(request("POST", "/api/orders/" + order.orderId() + "/confirm-purchase", buyerToken, null).status()).isEqualTo(200);
        } finally { buyerToken = previous; }
        return order;
    }
    Response write(OrderFixture order, String token, int rating) {
        return request("POST", "/api/orders/" + order.orderId() + "/reviews", token, Map.of("rating", rating, "content", "  좋은 거래  "));
    }
    Response trust(long id) { return request("GET", "/api/users/" + id + "/trust-info", strangerToken, null); }
    Response request(String method, String path, String token, Object body) {
        var request = RestClient.create("http://127.0.0.1:" + port).method(HttpMethod.valueOf(method)).uri(path);
        if (token != null) request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        if (body != null) request.body(body);
        return request.exchange((req, res) -> new Response(res.getStatusCode().value(), res.bodyTo(Map.class)));
    }
    void error(Response response, int status, String code) {
        assertThat(response.status()).isEqualTo(status);
        assertThat(response.body()).containsEntry("success", false).doesNotContainKey("data");
        assertThat(((Map<?, ?>) response.body().get("error")).get("code")).isEqualTo(code);
    }
    record OrderFixture(String orderId, String paymentId, long postId) {}
    @SuppressWarnings("unchecked")
    record Response(int status, Map<String, Object> body) {
        Map<String, Object> data() { return (Map<String, Object>) body.get("data"); }
        List<Map<String, Object>> rows() { return (List<Map<String, Object>>) data().get("content"); }
    }

    @Test void bothParticipantsCanRateOnceAndPublicResponsesExposeOnlyReviewFields() {
        var order = confirmed(buyer, seller);
        var first = write(order, buyerToken, 5);
        assertThat(first.status()).isEqualTo(201);
        assertThat(first.data()).containsEntry("content", "좋은 거래").containsEntry("authorRole", "BUYER")
            .containsEntry("recipientId", seller.getId().intValue());
        assertThat(write(order, sellerToken, 1).data()).containsEntry("authorRole", "SELLER");
        error(write(order, buyerToken, 2), 409, "REVIEW_002");
        error(write(order, sellerToken, 3), 409, "REVIEW_002");
        var publicRows = request("GET", "/api/users/" + seller.getId() + "/reviews", strangerToken, null).rows();
        assertThat(publicRows).hasSize(1);
        assertThat(publicRows.getFirst()).containsOnlyKeys("reviewId", "authorId", "recipientId", "authorRole", "rating", "content", "createdAt");
        var privateRows = request("GET", "/api/orders/" + order.orderId() + "/reviews", buyerToken, null);
        assertThat((List<?>) privateRows.body().get("data")).hasSize(2);
        error(request("GET", "/api/orders/" + order.orderId() + "/reviews", strangerToken, null), 403, "ORDER_004");
        assertThat(pgCalls.get()).isEqualTo(1);
    }

    @Test void paymentSuccessWithoutPurchaseConfirmationAndRefundedTradesCannotBeReviewed() {
        var pending = order(buyer, seller);
        error(write(pending, buyerToken, 5), 409, "REVIEW_001");
        approve(pending);
        error(write(pending, buyerToken, 5), 409, "REVIEW_001");
        assertThat(trust(seller.getId()).data()).containsEntry("completedTransactionCount", 0);
        pgResult(pending, "CANCELED");
        assertThat(request("POST", "/api/orders/" + pending.orderId() + "/cancel", buyerToken, Map.of("reason", "취소")).status()).isEqualTo(200);
        error(write(pending, sellerToken, 5), 409, "REVIEW_001");
        var refunded = order(buyer, seller); approve(refunded);
        jdbc.update("UPDATE orders SET order_status='REFUNDED' WHERE order_number=?", refunded.orderId());
        error(write(refunded, buyerToken, 5), 409, "REVIEW_001");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM transaction_reviews", Long.class)).isZero();
    }

    @Test void activeRefundBlocksReviewEvenIfOrderStatusIsInconsistent() {
        var order = confirmed(buyer, seller);
        jdbc.update("UPDATE orders SET refund_id='REF-TEST',refund_amount=amount,refund_reason='반품',refund_status='REQUESTED',refund_requested_at=CURRENT_TIMESTAMP WHERE order_number=?", order.orderId());
        error(write(order, buyerToken, 5), 409, "REVIEW_001");
    }

    @Test void unknownOrderStrangerMissingAuthenticationAndDeletedActorAreRejected() {
        var order = confirmed(buyer, seller);
        error(write(order, strangerToken, 5), 403, "ORDER_004");
        for (String token : new String[]{null, "invalid", tokens.createAccessToken(Long.MAX_VALUE)}) {
            error(write(order, token, 5), 401, "AUTH_004");
            error(request("GET", "/api/users/" + seller.getId() + "/trust-info", token, null), 401, "AUTH_004");
            error(request("GET", "/api/users/" + seller.getId() + "/reviews", token, null), 401, "AUTH_004");
        }
        error(request("POST", "/api/orders/ORD-UNKNOWN/reviews", buyerToken, Map.of("rating", 5, "content", "후기")), 404, "ORDER_001");
        error(request("POST", "/api/orders/1/reviews", buyerToken, Map.of("rating", 5, "content", "후기")), 400, "COMMON_002");
        error(trust(Long.MAX_VALUE), 404, "USER_001");
        error(trust(0), 400, "COMMON_001");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM transaction_reviews", Long.class)).isZero();
    }

    @Test void emptyAggregatesAndEmptyListsAreWellDefined() {
        var data = trust(seller.getId()).data();
        assertThat(data).containsOnlyKeys("userId", "completedTransactionCount", "reviewCount", "averageRating")
            .containsEntry("completedTransactionCount", 0).containsEntry("reviewCount", 0).containsEntry("averageRating", null);
        assertThat(request("GET", "/api/users/" + seller.getId() + "/reviews", buyerToken, null).rows()).isEmpty();
        var order = order(buyer, seller);
        assertThat((List<?>) request("GET", "/api/orders/" + order.orderId() + "/reviews", buyerToken, null).body().get("data")).isEmpty();
    }

    @Test void aggregationCountsBuyerAndSnapshotSellerAndRoundsExactRatingSum() {
        var first = confirmed(buyer, seller);
        var second = confirmed(stranger, seller);
        var third = confirmed(buyer, seller);
        var fourth = confirmed(seller, buyer);
        order(buyer, seller);
        assertThat(write(first, buyerToken, 1).status()).isEqualTo(201);
        assertThat(write(second, strangerToken, 2).status()).isEqualTo(201);
        assertThat(write(third, buyerToken, 2).status()).isEqualTo(201);
        assertThat(write(fourth, sellerToken, 4).status()).isEqualTo(201);
        var sellerTrust = trust(seller.getId()).data();
        assertThat(sellerTrust).containsEntry("completedTransactionCount", 4).containsEntry("reviewCount", 3).containsEntry("averageRating", 1.67);
        assertThat(trust(buyer.getId()).data()).containsEntry("completedTransactionCount", 3).containsEntry("reviewCount", 1).containsEntry("averageRating", 4.0);
        jdbc.update("UPDATE posts SET user_id=?,deleted_at=CURRENT_TIMESTAMP WHERE id=?", stranger.getId(), first.postId());
        jdbc.update("UPDATE users SET nickname='변경된 닉네임' WHERE id=?", seller.getId());
        assertThat(trust(seller.getId()).data()).isEqualTo(sellerTrust);
    }

    @Test void currentPostOwnerCannotReplaceSnapshotSeller() {
        var order = confirmed(buyer, seller);
        jdbc.update("UPDATE posts SET user_id=?,deleted_at=CURRENT_TIMESTAMP WHERE id=?", stranger.getId(), order.postId());
        error(write(order, strangerToken, 5), 403, "ORDER_004");
        assertThat(write(order, sellerToken, 5).status()).isEqualTo(201);
        assertThat(write(order, buyerToken, 4).data()).containsEntry("recipientId", seller.getId().intValue());
    }

    @Test void simultaneousDuplicateWritesHaveOneWinner() throws Exception {
        var order = confirmed(buyer, seller);
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> { start.await(); return write(order, buyerToken, 5); });
            var b = pool.submit(() -> { start.await(); return write(order, buyerToken, 1); });
            start.countDown();
            var responses = List.of(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS));
            assertThat(responses).extracting(Response::status).containsExactlyInAnyOrder(201, 409);
            error(responses.stream().filter(r -> r.status() == 409).findFirst().orElseThrow(), 409, "REVIEW_002");
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM transaction_reviews", Long.class)).isEqualTo(1);
        assertThat(trust(seller.getId()).data()).containsEntry("reviewCount", 1);
    }

    @Test void simultaneousOppositeParticipantWritesBothSucceed() throws Exception {
        var order = confirmed(buyer, seller);
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> { start.await(); return write(order, buyerToken, 5); });
            var b = pool.submit(() -> { start.await(); return write(order, sellerToken, 1); });
            start.countDown();
            assertThat(List.of(a.get(10, TimeUnit.SECONDS).status(), b.get(10, TimeUnit.SECONDS).status())).containsOnly(201);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM transaction_reviews", Long.class)).isEqualTo(2);
    }

    @Test void paginationIsStableWithoutLoadingUsersOrOrders() {
        for (int rating : List.of(1, 2, 3)) write(confirmed(buyer, seller), buyerToken, rating);
        jdbc.update("UPDATE transaction_reviews SET created_at=TIMESTAMP '2026-10-01 12:00:00'");
        var stats = entityManagerFactory.unwrap(SessionFactory.class).getStatistics(); stats.clear();
        var first = request("GET", "/api/users/" + seller.getId() + "/reviews?size=2", strangerToken, null);
        assertThat(first.data()).containsEntry("totalElements", 3).containsEntry("totalPages", 2);
        assertThat(first.rows()).extracting(row -> row.get("rating")).containsExactly(3, 2);
        assertThat(stats.getEntityFetchCount()).isZero();
        var next = request("GET", "/api/users/" + seller.getId() + "/reviews?size=2&page=1", strangerToken, null);
        assertThat(next.rows()).extracting(row -> row.get("rating")).containsExactly(1);
        assertThat(request("GET", "/api/users/" + seller.getId() + "/reviews?page=10000", strangerToken, null).rows()).isEmpty();
    }

    @Test void invalidInputNeverCreatesReview() {
        var order = confirmed(buyer, seller);
        for (Object rating : List.of(0, 6, 4.5, "wrong")) {
            var response = request("POST", "/api/orders/" + order.orderId() + "/reviews", buyerToken, Map.of("rating", rating, "content", "평가"));
            assertThat(response.status()).isEqualTo(400);
        }
        for (String content : List.of("", "   ", "\u2003", "가".repeat(1001)))
            error(request("POST", "/api/orders/" + order.orderId() + "/reviews", buyerToken, Map.of("rating", 5, "content", content)), 400, "COMMON_001");
        error(request("POST", "/api/orders/" + order.orderId() + "/reviews", buyerToken, Map.of("content", "평가")), 400, "COMMON_001");
        error(request("POST", "/api/orders/" + order.orderId() + "/reviews", buyerToken, Map.of("rating", 5)), 400, "COMMON_001");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM transaction_reviews", Long.class)).isZero();
        assertThat(write(order, buyerToken, 5).status()).isEqualTo(201);
    }

    @ParameterizedTest @ValueSource(strings = {"page=-1", "page=10001", "size=0", "size=101", "page=wrong"})
    void invalidPaginationIsRejected(String query) {
        assertThat(request("GET", "/api/users/" + seller.getId() + "/reviews?" + query, buyerToken, null).status()).isEqualTo(400);
    }
}
