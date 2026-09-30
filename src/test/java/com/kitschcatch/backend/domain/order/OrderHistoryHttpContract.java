// 실제 HTTP·JWT·DB로 거래 내역의 권한·상태·스냅샷·페이지와 읽기 전용 동작을 검증한다.
package com.kitschcatch.backend.domain.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.kitschcatch.backend.domain.order.entity.OrderStatus;
import com.kitschcatch.backend.domain.order.entity.PaymentStatus;
import com.kitschcatch.backend.domain.order.toss.TossPaymentResponse;
import com.kitschcatch.backend.domain.order.toss.TossPaymentsClient;
import com.kitschcatch.backend.domain.post.entity.*;
import com.kitschcatch.backend.domain.post.repository.PostRepository;
import com.kitschcatch.backend.domain.user.entity.AuthProvider;
import com.kitschcatch.backend.domain.user.entity.User;
import com.kitschcatch.backend.domain.user.repository.UserRepository;
import com.kitschcatch.backend.global.security.JwtTokenProvider;
import jakarta.persistence.EntityManagerFactory;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.client.RestClient;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;

abstract class OrderHistoryHttpContract {
    @LocalServerPort int port;
    @Autowired UserRepository users;
    @Autowired PostRepository posts;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtTokenProvider tokens;
    @Autowired EntityManagerFactory entityManagerFactory;
    @MockitoBean TossPaymentsClient toss;
    @MockitoBean S3Client s3;
    User buyer;
    User seller;
    User stranger;
    String buyerToken;
    String sellerToken;
    String strangerToken;

    @BeforeEach
    void resetData() {
        for (String table : List.of("payment_webhook_events", "payment_attempts", "payments", "orders", "post_images", "posts", "users")) {
            jdbc.update("DELETE FROM " + table);
        }
        buyer = user("구매자"); seller = user("판매자"); stranger = user("다른 사용자");
        buyerToken = tokens.createAccessToken(buyer.getId());
        sellerToken = tokens.createAccessToken(seller.getId());
        strangerToken = tokens.createAccessToken(stranger.getId());
    }

    @Test
    void buyerAndSellerSeeTheSameSnapshotAndPayment() {
        var order = order(buyer, seller, true);
        for (String token : List.of(buyerToken, sellerToken)) {
            var response = get("/api/orders/" + order.orderId(), token);
            assertThat(response.status()).isEqualTo(200);
            var data = response.data();
            assertThat(data).containsEntry("orderId", order.orderId()).containsEntry("status", "PENDING").containsEntry("amount", 12000);
            assertThat(child(data, "post")).containsEntry("title", "주문 당시 상품")
                .containsEntry("thumbnailUrl", "https://cdn.example.test/" + order.imageKey());
            assertThat(((Number) child(data, "post").get("id")).longValue()).isEqualTo(order.postId());
            assertThat(child(data, "buyer")).containsEntry("nickname", "구매자");
            assertThat(child(data, "seller")).containsEntry("nickname", "판매자");
            assertThat(child(data, "payment")).containsEntry("paymentId", order.paymentId())
                .containsEntry("method", "CARD").containsEntry("status", "READY");
            assertThat(data.get("orderedAt")).isNotNull();
            assertThat(data.get("reservationExpiresAt")).isNotNull();
        }
        verifyNoInteractions(toss, s3);
    }

    @Test
    void outsidersAreForbiddenAndUnknownOrdersAreNotFound() {
        var order = order(buyer, seller, true);
        assertError(get("/api/orders/" + order.orderId(), strangerToken), 403, "ORDER_004");
        assertError(get("/api/orders/ORD-UNKNOWN", buyerToken), 404, "ORDER_001");
        assertError(get("/api/orders/" + jdbc.queryForObject("SELECT id FROM orders", Long.class), buyerToken), 400, "COMMON_002");
    }

    @ParameterizedTest
    @ValueSource(strings = {"1", "-1", "ORD-", "ord-ABC", "ORD-ABC_123", "ORD- ABC", "ORD-AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"})
    void malformedOrderNumbersAreRejected(String number) {
        assertError(get("/api/orders/" + number, buyerToken), 400, "COMMON_002");
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/orders/ORD-UNKNOWN", "/api/users/me/purchase-orders", "/api/users/me/sale-orders"})
    void allEndpointsRequireAnAccessTokenForAnExistingUser(String path) {
        for (String token : new String[]{null, "invalid", tokens.createRefreshToken(buyer.getId()), tokens.createAccessToken(Long.MAX_VALUE)}) {
            assertError(get(path, token), 401, "AUTH_004");
        }
    }

    @Test
    void listsAreScopedByBuyerAndSnapshotSeller() {
        var purchase = order(buyer, seller, false);
        var sale = order(stranger, buyer, false);
        order(stranger, seller, false);
        assertThat(get("/api/users/me/purchase-orders", buyerToken).ids()).containsExactly(purchase.orderId());
        var sales = get("/api/users/me/sale-orders", buyerToken);
        assertThat(sales.ids()).containsExactly(sale.orderId());
        assertThat(child(sales.rows().getFirst(), "buyer")).containsEntry("nickname", stranger.getNickname());
        assertThat(get("/api/users/me/purchase-orders", sellerToken).rows()).isEmpty();
        assertThat(get("/api/users/me/sale-orders", strangerToken).rows()).isEmpty();
        assertThat(get("/api/users/me/purchase-orders?userId=" + buyer.getId(), sellerToken).rows()).isEmpty();
        assertError(get("/api/orders/" + purchase.orderId() + "?userId=" + buyer.getId(), strangerToken), 403, "ORDER_004");
    }

    @Test
    void changingCurrentPostOwnerDoesNotTransferHistoricalAccess() {
        var order = order(buyer, seller, false);
        jdbc.update("UPDATE posts SET user_id=? WHERE id=?", stranger.getId(), order.postId());
        assertThat(get("/api/users/me/sale-orders", sellerToken).ids()).containsExactly(order.orderId());
        assertThat(get("/api/users/me/sale-orders", strangerToken).rows()).isEmpty();
        assertThat(get("/api/orders/" + order.orderId(), sellerToken).status()).isEqualTo(200);
        assertError(get("/api/orders/" + order.orderId(), strangerToken), 403, "ORDER_004");
    }

    @Test
    void emptyAndOutOfRangePagesRetainConsistentTotals() {
        var empty = get("/api/users/me/purchase-orders", buyerToken);
        assertThat(empty.status()).isEqualTo(200);
        assertThat(empty.data()).containsEntry("page", 0).containsEntry("size", 20)
            .containsEntry("totalElements", 0).containsEntry("totalPages", 0);
        assertThat(empty.rows()).isEmpty();
        order(buyer, seller, false);
        for (var role : List.of(new String[]{"purchase-orders", buyerToken}, new String[]{"sale-orders", sellerToken})) {
            var page = get("/api/users/me/" + role[0] + "?page=10000&size=100", role[1]);
            assertThat(page.status()).isEqualTo(200);
            assertThat(page.rows()).isEmpty();
            assertThat(page.data()).containsEntry("totalElements", 1).containsEntry("totalPages", 1);
        }
    }

    @ParameterizedTest
    @EnumSource(OrderStatus.class)
    void everyOrderStatusCanBeFilteredWithoutConfusingPaymentStatus(OrderStatus status) {
        var matching = order(buyer, seller, false);
        var other = order(buyer, seller, false);
        jdbc.update("UPDATE orders SET order_status=? WHERE order_number=?", status.name(), matching.orderId());
        jdbc.update("UPDATE orders SET order_status=? WHERE order_number=?", status == OrderStatus.PAID ? "PENDING" : "PAID", other.orderId());
        for (var role : List.of(new String[]{"purchase-orders", buyerToken}, new String[]{"sale-orders", sellerToken})) {
            var page = get("/api/users/me/" + role[0] + "?status=" + status + "&size=1", role[1]);
            assertThat(page.ids()).containsExactly(matching.orderId());
            assertThat(page.data()).containsEntry("totalElements", 1).containsEntry("totalPages", 1);
            assertThat(page.rows().getFirst()).containsEntry("orderStatus", status.name()).containsEntry("paymentStatus", "READY");
        }
    }

    @ParameterizedTest
    @EnumSource(PaymentStatus.class)
    void allPersistedPaymentStatesRemainVisible(PaymentStatus status) {
        var order = order(buyer, seller, false);
        jdbc.update("UPDATE payments SET payment_status=? WHERE payment_id=?", status.name(), order.paymentId());
        var detail = get("/api/orders/" + order.orderId(), buyerToken);
        assertThat(child(detail.data(), "payment")).containsEntry("status", status.name());
        assertThat(get("/api/users/me/purchase-orders", buyerToken).rows().getFirst()).containsEntry("paymentStatus", status.name());
        assertThat(get("/api/users/me/sale-orders", sellerToken).rows().getFirst()).containsEntry("paymentStatus", status.name());
    }

    @ParameterizedTest
    @ValueSource(strings = {"page=-1", "page=10001", "page=2147483648", "page=x", "size=0", "size=101", "size=1.5", "status=", "status=paid", "status=SUCCESS", "status=UNKNOWN", "status= PAID "})
    void invalidListConditionsAreCommonInputErrors(String query) {
        assertError(get("/api/users/me/purchase-orders?" + query, buyerToken), 400, "COMMON_001");
        assertError(get("/api/users/me/sale-orders?" + query, sellerToken), 400, "COMMON_001");
    }

    @Test
    void listsUseCreatedTimeThenIdAndKeepTotalsAcrossPages() {
        var first = order(buyer, seller, false);
        var second = order(buyer, seller, false);
        var third = order(buyer, seller, false);
        jdbc.update("UPDATE orders SET created_at=TIMESTAMP '2026-09-30 00:00:00'");
        jdbc.update("UPDATE orders SET created_at=TIMESTAMP '2026-10-01 00:00:00' WHERE order_number=?", first.orderId());
        for (var role : List.of(new String[]{"purchase-orders", buyerToken}, new String[]{"sale-orders", sellerToken})) {
            String path = "/api/users/me/" + role[0] + "?size=2";
            var page = get(path, role[1]);
            assertThat(page.ids()).containsExactly(first.orderId(), third.orderId());
            assertThat(page.data()).containsEntry("totalElements", 3).containsEntry("totalPages", 2);
            assertThat(get(path + "&page=1", role[1]).ids()).containsExactly(second.orderId());
        }
    }

    @Test
    void maximumPageSizeDoesNotOmitRemainingOrders() {
        for (int i = 0; i < 101; i++) order(buyer, seller, false);
        for (var role : List.of(new String[]{"purchase-orders", buyerToken}, new String[]{"sale-orders", sellerToken})) {
            var first = get("/api/users/me/" + role[0] + "?size=100", role[1]);
            assertThat(first.rows()).hasSize(100);
            assertThat(first.data()).containsEntry("totalElements", 101).containsEntry("totalPages", 2);
            assertThat(get("/api/users/me/" + role[0] + "?size=100&page=1", role[1]).rows()).hasSize(1);
        }
    }

    @Test
    void ordersWithoutPaymentOrImageStillAppearWithNullFields() {
        var order = order(buyer, seller, false);
        jdbc.update("DELETE FROM payment_attempts");
        jdbc.update("DELETE FROM payments");
        var detail = get("/api/orders/" + order.orderId(), buyerToken);
        assertThat(detail.status()).isEqualTo(200);
        assertThat(detail.data()).containsEntry("payment", null);
        assertThat(child(detail.data(), "post")).containsEntry("thumbnailUrl", null);
        for (var role : List.of(new String[]{"purchase-orders", buyerToken}, new String[]{"sale-orders", sellerToken})) {
            var page = get("/api/users/me/" + role[0], role[1]);
            assertThat(page.ids()).containsExactly(order.orderId());
            assertThat(page.rows().getFirst()).containsEntry("paymentStatus", null);
        }
    }

    @Test
    void actualPaymentApprovalAndCancelFlowIsReflectedForBothParticipants() {
        var order = order(buyer, seller, false);
        approve(order);
        assertThat(get("/api/orders/" + order.orderId(), sellerToken).data()).containsEntry("status", "PAID");
        assertThat(get("/api/users/me/purchase-orders?status=PAID", buyerToken).rows().getFirst()).containsEntry("paymentStatus", "SUCCESS");
        cancel(order);
        var detail = get("/api/orders/" + order.orderId(), buyerToken);
        assertThat(detail.data()).containsEntry("status", "CANCELED");
        assertThat(child(detail.data(), "payment")).containsEntry("status", "CANCELED");
        assertThat(child(detail.data(), "payment").get("canceledAt")).isNotNull();
        assertThat(get("/api/users/me/sale-orders?status=CANCELED", sellerToken).ids()).containsExactly(order.orderId());
    }

    @Test
    void postEditsSoftDeletionAndNicknameChangesDoNotRewriteSnapshots() {
        var order = order(buyer, seller, true);
        approve(order); cancel(order);
        String replacementKey = "posts/" + seller.getId() + "/replacement.png";
        when(s3.headObject(any(HeadObjectRequest.class))).thenReturn(HeadObjectResponse.builder().build());
        var edited = request("PATCH", "/api/posts/" + order.postId(), sellerToken,
            Map.of("title", "바뀐 상품", "price", 25000, "imageKeys", List.of(replacementKey)));
        assertThat(edited.status()).isEqualTo(200);
        assertThat(request("DELETE", "/api/posts/" + order.postId(), sellerToken, null).status()).isEqualTo(200);
        jdbc.update("UPDATE users SET nickname='수정 닉네임' WHERE id IN (?, ?)", buyer.getId(), seller.getId());
        clearInvocations(s3, toss);
        var detail = get("/api/orders/" + order.orderId(), buyerToken);
        assertThat(detail.data()).containsEntry("amount", 12000);
        assertThat(child(detail.data(), "post")).containsEntry("title", "주문 당시 상품")
            .containsEntry("thumbnailUrl", "https://cdn.example.test/" + order.imageKey());
        assertThat(child(detail.data(), "buyer")).containsEntry("nickname", "구매자");
        assertThat(child(detail.data(), "seller")).containsEntry("nickname", "판매자");
        assertThat(get("/api/users/me/purchase-orders", buyerToken).rows().getFirst()).containsEntry("postTitle", "주문 당시 상품").containsEntry("amount", 12000);
        var sale = get("/api/users/me/sale-orders", sellerToken).rows().getFirst();
        assertThat(sale).containsEntry("postTitle", "주문 당시 상품");
        assertThat(child(sale, "buyer")).containsEntry("nickname", "구매자");
        verifyNoInteractions(s3, toss);
    }

    @Test
    void readingExpiredAndUncertainOrdersDoesNotMutateStateOrCallExternalServices() {
        var order = order(buyer, seller, true);
        jdbc.update("UPDATE orders SET reservation_expires_at=TIMESTAMP '2000-01-01 00:00:00'");
        jdbc.update("UPDATE payments SET payment_status='PROCESSING', processing_operation='CANCEL', recovery_state='REVIEW_REQUIRED'");
        var oldOrder = jdbc.queryForMap("SELECT * FROM orders");
        var oldPayment = jdbc.queryForMap("SELECT * FROM payments");
        var oldPost = jdbc.queryForMap("SELECT * FROM posts");
        clearInvocations(toss, s3);
        var detail = get("/api/orders/" + order.orderId(), sellerToken);
        assertThat(child(detail.data(), "payment")).containsEntry("status", "PROCESSING")
            .containsEntry("processingOperation", "CANCEL").containsEntry("recoveryState", "REVIEW_REQUIRED");
        get("/api/users/me/purchase-orders", buyerToken);
        get("/api/users/me/sale-orders", sellerToken);
        assertThat(jdbc.queryForMap("SELECT * FROM orders")).isEqualTo(oldOrder);
        assertThat(jdbc.queryForMap("SELECT * FROM payments")).isEqualTo(oldPayment);
        assertThat(jdbc.queryForMap("SELECT * FROM posts")).isEqualTo(oldPost);
        verifyNoInteractions(toss, s3);
    }

    @Test
    void responsesNeverExposePgKeysEmailOrInternalPaymentStateFields() {
        var order = order(buyer, seller, true);
        jdbc.update("UPDATE payments SET payment_key='secret-key', last_failure_code='internal-reason'");
        for (var route : List.of(new String[]{"/api/orders/" + order.orderId(), sellerToken},
                new String[]{"/api/users/me/purchase-orders", buyerToken}, new String[]{"/api/users/me/sale-orders", sellerToken})) {
            String body = get(route[0], route[1]).body().toString();
            assertThat(body).doesNotContain("secret-key", "paymentKey", "email", "internal-reason", "currentAttemptId", "pgIdempotencyKey", "stateVersion");
        }
    }

    @Test
    void twentyRowPagesAndDetailUseBoundedQueriesWithoutLazyFetches() {
        OrderFixture latest = null;
        for (int i = 0; i < 21; i++) latest = order(buyer, seller, true);
        var stats = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        for (var role : List.of(new String[]{"purchase-orders", buyerToken}, new String[]{"sale-orders", sellerToken})) {
            stats.clear();
            var page = get("/api/users/me/" + role[0], role[1]);
            assertThat(page.rows()).hasSize(20);
            assertThat(stats.getPrepareStatementCount()).as(role[0]).isBetween(1L, 3L);
            assertThat(stats.getEntityFetchCount()).isZero();
            assertThat(stats.getCollectionFetchCount()).isZero();
        }
        stats.clear();
        assertThat(get("/api/orders/" + latest.orderId(), sellerToken).status()).isEqualTo(200);
        assertThat(stats.getPrepareStatementCount()).isBetween(1L, 2L);
        assertThat(stats.getEntityFetchCount()).isZero();
        assertThat(stats.getCollectionFetchCount()).isZero();
    }

    @Test
    @SuppressWarnings("unchecked")
    void swaggerHasThreeAuthenticatedEndpointsAndCorrectErrorCodes() {
        var paths = (Map<String, Map<String, Map<String, Object>>>) get("/v3/api-docs", null).body().get("paths");
        for (String path : List.of("/api/orders/{orderId}", "/api/users/me/purchase-orders", "/api/users/me/sale-orders")) {
            var get = paths.get(path).get("get");
            assertThat(get.get("security").toString()).contains("bearerAuth");
            assertThat(get.get("parameters").toString()).doesNotContain("userId");
        }
        assertThat(paths.get("/api/orders/{orderId}").get("get").get("responses").toString()).contains("ORDER_004").doesNotContain("ORDER_002");
    }

    private void approve(OrderFixture order) {
        when(toss.confirm(any())).thenReturn(new TossPaymentResponse("pg-key-" + order.paymentId(), order.orderId(), 12000L, "DONE"));
        assertThat(request("POST", "/api/payments/" + order.paymentId() + "/confirm", buyerToken,
            Map.of("paymentId", order.paymentId(), "paymentKey", "pg-key-" + order.paymentId())).status()).isEqualTo(200);
    }
    private void cancel(OrderFixture order) {
        when(toss.cancel(any())).thenReturn(new TossPaymentResponse("pg-key-" + order.paymentId(), order.orderId(), 12000L, "CANCELED"));
        assertThat(request("POST", "/api/payments/" + order.paymentId() + "/cancel", buyerToken, null).status()).isEqualTo(200);
    }
    private OrderFixture order(User purchaser, User owner, boolean image) {
        var post = Post.builder().user(owner).title("주문 당시 상품").description("검증 상품").price(12000L)
            .productCategory(ProductCategory.GOODS).productCondition(ProductCondition.NEW).productStatus(ProductStatus.ON_SALE).build();
        String imageKey = "posts/" + owner.getId() + "/original.png";
        if (image) { post.addImage("posts/" + owner.getId() + "/later.png", 2); post.addImage(imageKey, 0); }
        post = posts.saveAndFlush(post);
        var response = request("POST", "/api/orders", tokens.createAccessToken(purchaser.getId()),
            Map.of("postId", post.getId(), "amount", 12000, "paymentMethod", "CARD"));
        assertThat(response.status()).isEqualTo(201);
        return new OrderFixture((String) response.data().get("orderId"), (String) response.data().get("paymentId"), post.getId(), imageKey);
    }
    private User user(String name) {
        String id = UUID.randomUUID().toString();
        return users.saveAndFlush(User.builder().nickname(name).email(id + "@example.test")
            .authProvider(AuthProvider.KAKAO).providerUserId(id).build());
    }
    private Response get(String path, String token) { return request("GET", path, token, null); }
    private Response request(String method, String path, String token, Object body) {
        var request = RestClient.create("http://127.0.0.1:" + port).method(HttpMethod.valueOf(method)).uri(path);
        if (token != null) request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        if (body != null) request.body(body);
        return request.exchange((req, res) -> new Response(res.getStatusCode().value(), res.bodyTo(Map.class)));
    }
    private void assertError(Response response, int status, String code) {
        assertThat(response.status()).isEqualTo(status);
        assertThat(response.body()).containsEntry("success", false).doesNotContainKey("data");
        assertThat(child(response.body(), "error")).containsEntry("code", code);
    }
    @SuppressWarnings("unchecked")
    private Map<String, Object> child(Map<String, Object> parent, String key) { return (Map<String, Object>) parent.get(key); }
    private record OrderFixture(String orderId, String paymentId, long postId, String imageKey) {}
    @SuppressWarnings("unchecked")
    private record Response(int status, Map<String, Object> body) {
        Map<String, Object> data() { return (Map<String, Object>) body.get("data"); }
        List<Map<String, Object>> rows() { return (List<Map<String, Object>>) data().get("content"); }
        List<String> ids() { return rows().stream().map(row -> (String) row.get("orderId")).toList(); }
    }
}
