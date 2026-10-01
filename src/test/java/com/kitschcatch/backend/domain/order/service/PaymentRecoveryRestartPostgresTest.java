// 실제 서버 컨텍스트 종료 후 재시작하여 PostgreSQL의 미확정 결제와 수신함을 로컬 PG 조회로 복구한다.
package com.kitschcatch.backend.domain.order.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.kitschcatch.backend.KitschcatchBackendApplication;
import com.kitschcatch.backend.domain.order.dto.*;
import com.kitschcatch.backend.domain.order.entity.*;
import com.kitschcatch.backend.domain.order.repository.*;
import com.kitschcatch.backend.domain.order.toss.TossPaymentsClient;
import com.kitschcatch.backend.domain.post.entity.*;
import com.kitschcatch.backend.domain.post.repository.PostRepository;
import com.kitschcatch.backend.domain.user.entity.*;
import com.kitschcatch.backend.domain.user.repository.UserRepository;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

@EnabledIfEnvironmentVariable(named = "ISSUE54_TEST_DB_URL", matches = ".+")
class PaymentRecoveryRestartPostgresTest {
    @Test
    void restartsServerAndRecoversPersistedCommandAndInboxWithoutApproval() throws Exception {
        HttpServer pg = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<String> pgOrderId = new AtomicReference<>();
        AtomicInteger lookups = new AtomicInteger();
        AtomicInteger approvals = new AtomicInteger();
        pg.createContext("/v1/payments/key", exchange -> {
            lookups.incrementAndGet();
            byte[] body = ("{\"paymentKey\":\"key\",\"orderId\":\"" + pgOrderId.get()
                + "\",\"totalAmount\":12000,\"status\":\"DONE\"}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        pg.createContext("/v1/payments/confirm", exchange -> {
            approvals.incrementAndGet();
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        });
        pg.start();
        try (var db = new Issue54PostgresDatabase()) {
            CreateOrderResponse order;
            Long buyerId;
            String baseUrl = "http://127.0.0.1:" + pg.getAddress().getPort();
            try (var first = server(db, baseUrl, "create")) {
                var users = first.getBean(UserRepository.class);
                var seller = users.save(user("seller"));
                buyerId = users.save(user("buyer")).getId();
                var post = first.getBean(PostRepository.class).save(Post.builder().user(seller).title("키링")
                    .description("재시작 검증").price(12000L).productCategory(ProductCategory.GOODS)
                    .productCondition(ProductCondition.NEW).productStatus(ProductStatus.ON_SALE).build());
                order = first.getBean(OrderService.class).createOrder(buyerId,
                    new CreateOrderRequest(post.getId(), 12000L, PaymentMethod.CARD));
                pgOrderId.set(order.pgOrderId());
                first.getBean(PaymentTransactionService.class).startConfirm(buyerId, order.paymentId(),
                    new ConfirmPaymentRequest(order.paymentId(), "key", order.attemptId()));
                var webhook = first.getBean(PaymentWebhookService.class);
                webhook.receive("restart-54", new TossPaymentWebhookRequest("PAYMENT_STATUS_CHANGED",
                    "2026-10-01T12:00:00+09:00", new TossPaymentWebhookRequest.TossPaymentWebhookData(
                        "key", order.pgOrderId(), 12000L, null, "DONE")));
                var event = first.getBean(PaymentWebhookEventRepository.class).findAll().getFirst();
                webhook.claim(event.getId(), "abandoned", LocalDateTime.now().plusMinutes(1));
                first.getBean(JdbcTemplate.class).update("UPDATE payment_webhook_events SET lease_until=?",
                    LocalDateTime.now().minusSeconds(1));
                assertThat(first.getBean(PaymentService.class).getPayment(buyerId, order.paymentId()).status())
                    .isEqualTo(PaymentStatus.PROCESSING);
            }
            try (var restarted = server(db, baseUrl, "validate")) {
                var recovery = restarted.getBean(PaymentRecoveryService.class);
                new PaymentRecoveryScheduler(restarted.getBean(PaymentAttemptRepository.class),
                    restarted.getBean(TossPaymentsClient.class), recovery, 20).recoverPayments();
                new PaymentWebhookProcessingService(restarted.getBean(PaymentWebhookEventRepository.class),
                    restarted.getBean(PaymentAttemptRepository.class), restarted.getBean(PaymentWebhookService.class),
                    recovery, restarted.getBean(TossPaymentsClient.class), 20).processWebhooks();
                assertThat(restarted.getBean(PaymentService.class).getPayment(buyerId, order.paymentId()).status())
                    .isEqualTo(PaymentStatus.SUCCESS);
                assertThat(restarted.getBean(PaymentAttemptRepository.class).count()).isEqualTo(1);
                assertThat(restarted.getBean(PaymentWebhookEventRepository.class).findAll().getFirst().getProcessingStatus())
                    .isEqualTo(PaymentWebhookProcessingStatus.PROCESSED);
                assertThat(approvals.get()).isZero();
                assertThat(lookups.get()).isEqualTo(2);
            }
        } finally {
            pg.stop(0);
        }
    }

    private ConfigurableApplicationContext server(Issue54PostgresDatabase db, String pgUrl, String ddl) {
        return new SpringApplicationBuilder(KitschcatchBackendApplication.class).run(
            "--server.port=0", "--server.address=127.0.0.1", "--spring.datasource.url=" + db.scopedUrl(),
            "--spring.datasource.username=" + db.user, "--spring.datasource.password=" + db.password,
            "--spring.datasource.driver-class-name=org.postgresql.Driver", "--spring.jpa.hibernate.ddl-auto=" + ddl,
            "--app.orders.expiration-enabled=false", "--app.payments.recovery.enabled=false",
            "--kakao.oauth.native-app-key=local-test-key",
            "--app.toss-payments.base-url=" + pgUrl, "--app.toss-payments.secret-key=local-test-key");
    }

    private User user(String name) {
        return User.builder().nickname(name).email(name + "@example.com").authProvider(AuthProvider.KAKAO)
            .providerUserId(name).build();
    }
}
