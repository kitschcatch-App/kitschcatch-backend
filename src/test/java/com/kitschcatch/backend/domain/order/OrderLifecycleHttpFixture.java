// 거래 후속 처리의 실제 HTTP·JWT·주문 생성·PG 승인 검증 기반을 제공한다.
package com.kitschcatch.backend.domain.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.reset;

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

abstract class OrderLifecycleHttpFixture {
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
        reset(toss, s3);
        buyer = user("구매자"); seller = user("판매자"); stranger = user("다른 사용자");
        buyerToken = tokens.createAccessToken(buyer.getId());
        sellerToken = tokens.createAccessToken(seller.getId());
        strangerToken = tokens.createAccessToken(stranger.getId());
    }

    protected void approve(OrderFixture order) {
        when(toss.confirm(any())).thenReturn(new TossPaymentResponse("pg-key-" + order.paymentId(), order.orderId(), 12000L, "DONE"));
        assertThat(request("POST", "/api/payments/" + order.paymentId() + "/confirm", buyerToken,
            Map.of("paymentId", order.paymentId(), "paymentKey", "pg-key-" + order.paymentId())).status()).isEqualTo(200);
    }
    protected void cancel(OrderFixture order) {
        when(toss.cancel(any())).thenReturn(new TossPaymentResponse("pg-key-" + order.paymentId(), order.orderId(), 12000L, "CANCELED"));
        assertThat(request("POST", "/api/payments/" + order.paymentId() + "/cancel", buyerToken, null).status()).isEqualTo(200);
    }
    protected OrderFixture order(User purchaser, User owner, boolean image) {
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
    protected User user(String name) {
        String id = UUID.randomUUID().toString();
        return users.saveAndFlush(User.builder().nickname(name).email(id + "@example.test")
            .authProvider(AuthProvider.KAKAO).providerUserId(id).build());
    }
    protected Response get(String path, String token) { return request("GET", path, token, null); }
    protected Response request(String method, String path, String token, Object body) {
        var request = RestClient.create("http://127.0.0.1:" + port).method(HttpMethod.valueOf(method)).uri(path);
        if (token != null) request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        if (body != null) request.body(body);
        return request.exchange((req, res) -> new Response(res.getStatusCode().value(), res.bodyTo(Map.class)));
    }
    protected void assertError(Response response, int status, String code) {
        assertThat(response.status()).isEqualTo(status);
        assertThat(response.body()).containsEntry("success", false).doesNotContainKey("data");
        assertThat(child(response.body(), "error")).containsEntry("code", code);
    }
    @SuppressWarnings("unchecked")
    protected Map<String, Object> child(Map<String, Object> parent, String key) { return (Map<String, Object>) parent.get(key); }
    protected record OrderFixture(String orderId, String paymentId, long postId, String imageKey) {}
    @SuppressWarnings("unchecked")
    protected record Response(int status, Map<String, Object> body) {
        Map<String, Object> data() { return (Map<String, Object>) body.get("data"); }
        List<Map<String, Object>> rows() { return (List<Map<String, Object>>) data().get("content"); }
        List<String> ids() { return rows().stream().map(row -> (String) row.get("orderId")).toList(); }
    }
}
