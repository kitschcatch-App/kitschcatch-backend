// 격리 PostgreSQL의 실제 잠금과 부분 유일 인덱스로 복구 워커·재시도·웹훅 경합을 검증한다.
package com.kitschcatch.backend.domain.order.service;

import static com.kitschcatch.backend.domain.order.toss.TossPaymentFixtures.cardApproval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.kitschcatch.backend.domain.order.dto.*;
import com.kitschcatch.backend.domain.order.entity.*;
import com.kitschcatch.backend.domain.order.repository.*;
import com.kitschcatch.backend.domain.order.toss.*;
import com.kitschcatch.backend.domain.post.entity.*;
import com.kitschcatch.backend.domain.post.repository.PostRepository;
import com.kitschcatch.backend.domain.user.entity.*;
import com.kitschcatch.backend.domain.user.repository.UserRepository;
import com.kitschcatch.backend.global.exception.BusinessException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@EnabledIfEnvironmentVariable(named = "ISSUE54_TEST_DB_URL", matches = ".+")
@DataJpaTest(showSql = false, properties = {"spring.jpa.hibernate.ddl-auto=create-drop"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({com.kitschcatch.backend.domain.address.service.ShippingAddressService.class, OrderService.class, OrderReservationService.class, PaymentTransactionService.class,
    PaymentService.class, PaymentRecoveryService.class, PaymentWebhookService.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PaymentRecoveryWorkerPostgresTest {
    static Issue54PostgresDatabase database;
    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) throws Exception {
        database = new Issue54PostgresDatabase();
        registry.add("spring.datasource.url", database::scopedUrl);
        registry.add("spring.datasource.username", () -> database.user);
        registry.add("spring.datasource.password", () -> database.password);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }
    @AfterAll
    static void closeSchema() throws Exception {
        if (database != null) database.close();
    }

    @Autowired OrderService orders;
    @Autowired PaymentService payments;
    @Autowired PaymentTransactionService transactions;
    @Autowired PaymentRecoveryService recovery;
    @Autowired OrderReservationService reservations;
    @Autowired PaymentWebhookService webhooks;
    @Autowired PaymentAttemptRepository attempts;
    @Autowired PaymentWebhookEventRepository events;
    @Autowired PaymentRepository paymentRepository;
    @Autowired PurchaseOrderRepository orderRepository;
    @Autowired UserRepository users;
    @Autowired PostRepository posts;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean TossPaymentsClient pg;
    Long buyerId;
    Long sellerId;
    Long postId;
    CreateOrderResponse order;

    @BeforeEach
    void fixture() throws Exception {
        reset(pg);
        jdbc.execute("TRUNCATE users, payment_webhook_events RESTART IDENTITY CASCADE");
        try (var stream = getClass().getResourceAsStream("/db/manual/029_payment_recovery.sql")) {
            String sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            String indexes = sql.substring(sql.indexOf("CREATE UNIQUE INDEX uk_payment_attempts_confirm_pg_order_id"),
                sql.indexOf("CREATE TABLE payment_webhook_events"));
            jdbc.execute(indexes.replace("CREATE UNIQUE INDEX ", "CREATE UNIQUE INDEX IF NOT EXISTS "));
        }
        User seller = users.save(user("seller"));
        sellerId = seller.getId();
        buyerId = users.save(user("buyer")).getId();
        postId = posts.save(Post.builder().user(seller).title("키링").description("검증 상품").price(12000L)
            .productCategory(ProductCategory.GOODS).productCondition(ProductCondition.NEW)
            .productStatus(ProductStatus.ON_SALE).build()).getId();
        order = orders.createOrder(buyerId, new CreateOrderRequest(postId, 12000L, PaymentMethod.CARD));
    }

    @Test
    void twoWorkersQueryExactlyOnceAndRecoverWithoutReapproval() throws Exception {
        start();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        when(pg.getPayment("key")).thenAnswer(invocation -> {
            entered.countDown();
            assertThat(finish.await(10, TimeUnit.SECONDS)).isTrue();
            return result("DONE");
        });
        try (var executor = Executors.newSingleThreadExecutor()) {
            Future<?> first = executor.submit(() -> scheduler().recoverPayments());
            try {
                assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
                scheduler().recoverPayments();
                assertThat(payments.getPayment(buyerId, order.paymentId()).status()).isEqualTo(PaymentStatus.PROCESSING);
            } finally {
                finish.countDown();
            }
            first.get(15, TimeUnit.SECONDS);
        }
        assertThat(payments.getPayment(buyerId, order.paymentId()).status()).isEqualTo(PaymentStatus.SUCCESS);
        assertThat(posts.findById(postId).orElseThrow().getProductStatus()).isEqualTo(ProductStatus.SOLD_OUT);
        verify(pg, times(1)).getPayment("key");
        verify(pg, never()).confirm(any());
        assertThat(attempts.count()).isEqualTo(1);
    }

    @ParameterizedTest
    @CsvSource({
        "DONE,간편결제,신용", "DONE,계좌이체,", "DONE,카드,기프트",
        "DONE,카드,미확인", "DONE,,", "DONE,카드,", "WAITING_FOR_DEPOSIT,가상계좌,"
    })
    void unsupportedMethodsAreCanceledWithoutMarkingOrderPaid(String status, String method, String type) {
        when(pg.confirm(any())).thenReturn(new TossPaymentResponse("key", order.pgOrderId(), 12000L, status,
            12000L, null, null, null, method, type == null ? null : new TossPaymentResponse.Card(type)));
        when(pg.cancel(any())).thenAnswer(invocation -> {
            assertThat(orderRepository.findById(localOrderId()).orElseThrow().getOrderStatus()).isEqualTo(OrderStatus.PENDING);
            assertThat(posts.findById(postId).orElseThrow().getProductStatus()).isEqualTo(ProductStatus.RESERVED);
            return result("CANCELED");
        });

        var response = payments.confirmPayment(buyerId, order.paymentId(), request());
        assertThat(response.status()).isEqualTo(PaymentStatus.CANCELED);
        assertThat(response.failureCode()).isEqualTo(Payment.UNSUPPORTED_METHOD_CODE);
        assertThat(response.approvedAt()).isNull();
        assertThat(response.retryAllowed()).isFalse();
        assertThat(orderRepository.findById(localOrderId()).orElseThrow().getOrderStatus()).isEqualTo(OrderStatus.CANCELED);
        assertThat(posts.findById(postId).orElseThrow().getProductStatus()).isEqualTo(ProductStatus.ON_SALE);
        assertThat(payments.confirmPayment(buyerId, order.paymentId(), request()).status()).isEqualTo(PaymentStatus.CANCELED);
        verify(pg, times(1)).confirm(any());
        verify(pg, times(1)).cancel(new TossPaymentCancelRequest("key", "지원하지 않는 결제 수단", "cancel-key"));
    }

    @Test
    void lostCancellationResponseKeepsReservationAndReplaysWithSameKey() {
        var unsupported = new TossPaymentResponse("key", order.pgOrderId(), 12000L, "DONE",
            12000L, null, null, null, "간편결제", null);
        when(pg.confirm(any())).thenReturn(unsupported);
        when(pg.cancel(any())).thenThrow(new TossPaymentException("TRANSPORT_ERROR", false))
            .thenReturn(new TossPaymentResponse("key", order.pgOrderId(), 12000L, "CANCELED", 2000L, null, null, null));
        var pending = payments.confirmPayment(buyerId, order.paymentId(), request());
        assertThat(pending.status()).isEqualTo(PaymentStatus.PROCESSING);
        assertThat(pending.processingOperation()).isEqualTo(PaymentOperation.CANCEL);
        assertThat(pending.retryAllowed()).isFalse();
        expireReservation();
        assertThat(reservations.expireOrder(localOrderId())).isFalse();
        recovery.recover(order.attemptId(), unsupported);
        assertThat(payments.confirmPayment(buyerId, order.paymentId(), request()).failureCode())
            .isEqualTo(Payment.UNSUPPORTED_METHOD_CODE);

        when(pg.getPayment("key")).thenReturn(new TossPaymentResponse("other-key", order.pgOrderId(), 12000L, "DONE"))
            .thenReturn(unsupported);
        makeDue(pending.attemptId());
        scheduler().recoverPayments();
        verify(pg, times(1)).cancel(any());
        assertThat(posts.findById(postId).orElseThrow().getProductStatus()).isEqualTo(ProductStatus.RESERVED);

        makeDue(pending.attemptId());
        scheduler().recoverPayments();
        verify(pg, times(2)).cancel(new TossPaymentCancelRequest("key", "지원하지 않는 결제 수단", "cancel-key"));
        assertThat(payments.getPayment(buyerId, order.paymentId()).status()).isEqualTo(PaymentStatus.PROCESSING);
        assertThat(posts.findById(postId).orElseThrow().getProductStatus()).isEqualTo(ProductStatus.RESERVED);

        when(pg.getPayment("key")).thenReturn(result("CANCELED"));
        makeDue(pending.attemptId());
        scheduler().recoverPayments();
        verify(pg, times(2)).cancel(any());
        verify(pg, times(1)).confirm(any());
        var canceled = payments.getPayment(buyerId, order.paymentId());
        assertThat(canceled.status()).isEqualTo(PaymentStatus.CANCELED);
        assertThat(canceled.failureCode()).isEqualTo(Payment.UNSUPPORTED_METHOD_CODE);
        assertThat(posts.findById(postId).orElseThrow().getProductStatus()).isEqualTo(ProductStatus.ON_SALE);
        assertThat(attempts.count()).isEqualTo(2);
    }

    @Test
    void webhookDiscoveryQueuesCancellationAndAnAlreadyCanceledLookupAvoidsAnotherPgCall() {
        start();
        receiveWebhook();
        when(pg.getPayment("key")).thenReturn(new TossPaymentResponse("key", order.pgOrderId(), 12000L, "DONE",
            12000L, null, null, null, "계좌이체", null)).thenReturn(result("CANCELED"));
        webhookWorker().processWebhooks();
        var pending = payments.getPayment(buyerId, order.paymentId());
        assertThat(pending.processingOperation()).isEqualTo(PaymentOperation.CANCEL);
        assertThat(posts.findById(postId).orElseThrow().getProductStatus()).isEqualTo(ProductStatus.RESERVED);
        scheduler().recoverPayments();
        verify(pg, never()).cancel(any());
        verify(pg, never()).confirm(any());
        assertThat(payments.getPayment(buyerId, order.paymentId()).status()).isEqualTo(PaymentStatus.CANCELED);
        jdbc.update("UPDATE payment_webhook_events SET next_process_at=?", LocalDateTime.now());
        webhookWorker().processWebhooks();
        assertThat(events.findAll().getFirst().getProcessingStatus()).isEqualTo(PaymentWebhookProcessingStatus.PROCESSED);
    }

    @ParameterizedTest
    @CsvSource({"간편결제, true", "카드, false"})
    void recoveryOfPartiallyCanceledPaymentOnlyCancelsRemainderForUnsupportedMethods(String method, boolean unsupported) {
        start();
        when(pg.getPayment("key")).thenReturn(new TossPaymentResponse("key", order.pgOrderId(), 12000L,
            "PARTIAL_CANCELED", 2000L, null, null, null, method, new TossPaymentResponse.Card("신용")));

        scheduler().recoverPayments();
        var pending = payments.getPayment(buyerId, order.paymentId());
        assertThat(pending.status()).isEqualTo(PaymentStatus.PROCESSING);
        assertThat(posts.findById(postId).orElseThrow().getProductStatus()).isEqualTo(ProductStatus.RESERVED);
        verify(pg, never()).cancel(any());
        if (unsupported) {
            assertThat(pending.processingOperation()).isEqualTo(PaymentOperation.CANCEL);
            when(pg.cancel(any())).thenReturn(result("CANCELED"));
            scheduler().recoverPayments();
            verify(pg).cancel(new TossPaymentCancelRequest("key", "지원하지 않는 결제 수단", "cancel-key"));
            var canceled = payments.getPayment(buyerId, order.paymentId());
            assertThat(canceled.status()).isEqualTo(PaymentStatus.CANCELED);
            assertThat(canceled.failureCode()).isEqualTo(Payment.UNSUPPORTED_METHOD_CODE);
            assertThat(posts.findById(postId).orElseThrow().getProductStatus()).isEqualTo(ProductStatus.ON_SALE);
        } else {
            assertThat(pending.processingOperation()).isEqualTo(PaymentOperation.CONFIRM);
            assertThat(pending.recoveryState()).isEqualTo(PaymentRecoveryState.REVIEW_REQUIRED);
            assertThat(attempts.count()).isEqualTo(1);
        }
        verify(pg, never()).confirm(any());
    }

    private void makeDue(String attemptId) {
        jdbc.update("UPDATE payment_attempts SET next_check_at=? WHERE attempt_id=?", LocalDateTime.now(), attemptId);
    }

    @Test
    void expiredLeaseAndFormerOwnerCannotApplySuccessOrFailure() {
        start();
        assertThat(recovery.claim(order.attemptId(), "old", LocalDateTime.now().plusMinutes(1))).isTrue();
        jdbc.update("UPDATE payment_attempts SET lease_until=? WHERE attempt_id=?",
            LocalDateTime.now().minusSeconds(1), order.attemptId());
        recovery.recover(order.attemptId(), result("DONE"), "old");
        recovery.recordLookupFailure(order.attemptId(), "old-error", "old");
        assertThat(attempts.findByAttemptId(order.attemptId()).orElseThrow().getAttemptStatus())
            .isEqualTo(PaymentAttemptStatus.PROCESSING);
        assertThat(recovery.claim(order.attemptId(), "new", LocalDateTime.now().plusMinutes(1))).isTrue();
        recovery.recover(order.attemptId(), result("ABORTED"), "old");
        recovery.recordLookupFailure(order.attemptId(), "old-error", "old");
        recovery.recover(order.attemptId(), result("DONE"), "new");
        assertThat(payments.getPayment(buyerId, order.paymentId()).status()).isEqualTo(PaymentStatus.SUCCESS);
    }

    @Test
    void uncertainPgErrorProtectsReservationAndDuplicateRequestDoesNotCallPg() {
        when(pg.confirm(any())).thenThrow(new TossPaymentException("PROVIDER_ERROR", false));
        assertThat(payments.confirmPayment(buyerId, order.paymentId(), request()).status()).isEqualTo(PaymentStatus.PROCESSING);
        assertThat(payments.confirmPayment(buyerId, order.paymentId(), request()).status()).isEqualTo(PaymentStatus.PROCESSING);
        expireReservation();
        assertThat(reservations.expireOrder(localOrderId())).isFalse();
        assertThatThrownBy(() -> payments.prepareRetry(buyerId, order.paymentId(),
            new RetryPaymentRequest(order.attemptId()))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> payments.confirmPayment(buyerId, order.paymentId(),
            new ConfirmPaymentRequest(order.paymentId(), "different", order.attemptId())))
            .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> payments.confirmPayment(999L, order.paymentId(), request()))
            .isInstanceOf(BusinessException.class);
        verify(pg, times(1)).confirm(any());
        assertThat(posts.findById(postId).orElseThrow().getProductStatus()).isEqualTo(ProductStatus.RESERVED);
        assertThat(payments.getPayment(buyerId, order.paymentId()).retryAllowed()).isFalse();
    }

    @Test
    void confirmedRejectionAllowsExactlyOneConcurrentRetryAndRequiresNewAttemptId() throws Exception {
        when(pg.confirm(any())).thenThrow(new TossPaymentException("REJECT_CARD_PAYMENT", true));
        var rejected = payments.confirmPayment(buyerId, order.paymentId(), request());
        assertThat(rejected.status()).isEqualTo(PaymentStatus.FAILED);
        assertThat(rejected.failureCode()).isEqualTo("REJECT_CARD_PAYMENT");
        assertThat(rejected.retryAllowed()).isTrue();
        try (var executor = Executors.newFixedThreadPool(2)) {
            CountDownLatch start = new CountDownLatch(1);
            Callable<RetryPaymentResponse> retry = () -> {
                assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                return payments.prepareRetry(buyerId, order.paymentId(), new RetryPaymentRequest(order.attemptId()));
            };
            var a = executor.submit(retry);
            var b = executor.submit(retry);
            start.countDown();
            var issued = a.get(15, TimeUnit.SECONDS);
            assertThat(b.get(15, TimeUnit.SECONDS).attemptId()).isEqualTo(issued.attemptId());
            assertThat(attempts.count()).isEqualTo(2);
            assertThatThrownBy(() -> payments.confirmPayment(buyerId, order.paymentId(),
                new ConfirmPaymentRequest(order.paymentId(), "new-key"))).isInstanceOf(BusinessException.class);
            doReturn(cardApproval("new-key", issued.pgOrderId(), 12000L)).when(pg).confirm(any());
            payments.confirmPayment(buyerId, order.paymentId(),
                new ConfirmPaymentRequest(order.paymentId(), "new-key", issued.attemptId()));
            assertThat(payments.prepareRetry(buyerId, order.paymentId(),
                new RetryPaymentRequest(order.attemptId())).nextAction()).isEqualTo("NONE");
        }
    }

    @Test
    void contradictionOnOldFailedAttemptBlocksExpiryWithoutCreatingAnotherActiveAttempt() {
        when(pg.confirm(any())).thenThrow(new TossPaymentException("REJECT_CARD_COMPANY", true));
        payments.confirmPayment(buyerId, order.paymentId(), request());
        payments.prepareRetry(buyerId, order.paymentId(), new RetryPaymentRequest(order.attemptId()));
        recovery.recover(order.attemptId(), result("DONE"));
        expireReservation();
        assertThat(reservations.expireOrder(localOrderId())).isFalse();
        assertThat(payments.getPayment(buyerId, order.paymentId()).recoveryState()).isEqualTo(PaymentRecoveryState.REVIEW_REQUIRED);
        assertThat(attempts.findByAttemptId(order.attemptId()).orElseThrow().getAttemptStatus()).isEqualTo(PaymentAttemptStatus.FAILED);
        assertThat(attempts.count()).isEqualTo(2);
    }

    @Test
    void webhookCancellationWinsAgainstLateNormalApprovalAndReprocessingIsIdempotent() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        when(pg.confirm(any())).thenAnswer(invocation -> {
            entered.countDown();
            assertThat(finish.await(15, TimeUnit.SECONDS)).isTrue();
            return result("DONE");
        });
        when(pg.getPayment("key")).thenReturn(result("CANCELED"));
        try (var executor = Executors.newSingleThreadExecutor()) {
            var normal = executor.submit(() -> payments.confirmPayment(buyerId, order.paymentId(), request()));
            try {
                assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
                receiveWebhook();
                webhookWorker().processWebhooks();
                var inbox = events.findAll().getFirst();
                assertThat(inbox.getProcessingStatus()).withFailMessage("수신함 상태=%s, 이유=%s",
                    inbox.getProcessingStatus(), inbox.getFailureReason())
                    .isEqualTo(PaymentWebhookProcessingStatus.PROCESSED);
                assertThat(payments.getPayment(buyerId, order.paymentId()).status()).isEqualTo(PaymentStatus.CANCELED);
            } finally {
                finish.countDown();
            }
            assertThat(normal.get(10, TimeUnit.SECONDS).status()).isEqualTo(PaymentStatus.CANCELED);
        }
        // 결과 반영 직후 수신함 완료 기록을 놓친 재시작을 재현한다.
        jdbc.update("UPDATE payment_webhook_events SET processing_status='RETRY_WAIT',next_process_at=?", LocalDateTime.now());
        webhookWorker().processWebhooks();
        assertThat(payments.getPayment(buyerId, order.paymentId()).status()).isEqualTo(PaymentStatus.CANCELED);
        assertThat(posts.findById(postId).orElseThrow().getProductStatus()).isEqualTo(ProductStatus.ON_SALE);
        assertThat(attempts.count()).isEqualTo(1);
        assertThat(events.findAll().getFirst().getProcessingStatus()).isEqualTo(PaymentWebhookProcessingStatus.PROCESSED);
    }

    @Test
    void persistedInboxSurvivesWorkerRestartAndStaleInboxLeaseCannotCompleteIt() {
        start();
        receiveWebhook();
        var event = events.findAll().getFirst();
        webhooks.claim(event.getId(), "abandoned", LocalDateTime.now().plusMinutes(1));
        jdbc.update("UPDATE payment_webhook_events SET lease_until=?", LocalDateTime.now().minusSeconds(1));
        webhooks.markProcessed(event.getId(), "abandoned");
        assertThat(events.findById(event.getId()).orElseThrow().getProcessingStatus()).isEqualTo(PaymentWebhookProcessingStatus.PROCESSING);
        when(pg.getPayment("key")).thenReturn(result("DONE"));
        webhookWorker().processWebhooks();
        assertThat(payments.getPayment(buyerId, order.paymentId()).status()).isEqualTo(PaymentStatus.SUCCESS);
        assertThat(events.findById(event.getId()).orElseThrow().getProcessingStatus()).isEqualTo(PaymentWebhookProcessingStatus.PROCESSED);
    }

    @Test
    void preparedAttemptCannotBeClaimedAndMismatchedSuccessfulLookupCannotCancelIt() {
        assertThat(recovery.claim(order.attemptId(), "worker", LocalDateTime.now().plusMinutes(1))).isFalse();
        start();
        recovery.recover(order.attemptId(), result("DONE"));
        recovery.recover(order.attemptId(), new TossPaymentResponse("other", order.pgOrderId(), 12000L, "CANCELED"));
        assertThat(payments.getPayment(buyerId, order.paymentId()).status()).isEqualTo(PaymentStatus.SUCCESS);
        assertThat(payments.getPayment(buyerId, order.paymentId()).recoveryState()).isEqualTo(PaymentRecoveryState.REVIEW_REQUIRED);
        assertThat(posts.findById(postId).orElseThrow().getProductStatus()).isEqualTo(ProductStatus.SOLD_OUT);
    }

    @Test
    void mismatchedOldestCandidateDoesNotStarveTheNextPaymentWithBatchSizeOne() {
        start();
        var nextPost = posts.save(Post.builder().user(users.findById(sellerId).orElseThrow()).title("다음 상품")
            .description("배치 공정성 검증").price(12000L).productCategory(ProductCategory.GOODS)
            .productCondition(ProductCondition.NEW).productStatus(ProductStatus.ON_SALE).build());
        var nextOrder = orders.createOrder(buyerId, new CreateOrderRequest(nextPost.getId(), 12000L, PaymentMethod.CARD));
        transactions.startConfirm(buyerId, nextOrder.paymentId(),
            new ConfirmPaymentRequest(nextOrder.paymentId(), "key-2", nextOrder.attemptId()));
        jdbc.update("UPDATE payment_attempts SET next_check_at=? WHERE attempt_id=?",
            LocalDateTime.now().minusHours(1), order.attemptId());
        when(pg.getPayment("key")).thenReturn(cardApproval("mismatch", order.pgOrderId(), 12000L));
        when(pg.getPayment("key-2")).thenReturn(cardApproval("key-2", nextOrder.pgOrderId(), 12000L));
        var worker = new PaymentRecoveryScheduler(attempts, pg, recovery, 1);
        worker.recoverPayments();
        assertThat(payments.getPayment(buyerId, order.paymentId()).recoveryState()).isEqualTo(PaymentRecoveryState.REVIEW_REQUIRED);
        assertThat(attempts.findByAttemptId(order.attemptId()).orElseThrow().getNextCheckAt())
            .isAfter(LocalDateTime.now().plusMinutes(14));
        worker.recoverPayments();
        assertThat(payments.getPayment(buyerId, nextOrder.paymentId()).status()).isEqualTo(PaymentStatus.SUCCESS);
        verify(pg, times(1)).getPayment("key");
        verify(pg, times(1)).getPayment("key-2");
    }

    PaymentRecoveryScheduler scheduler() {
        return new PaymentRecoveryScheduler(attempts, pg, recovery, 20);
    }
    PaymentWebhookProcessingService webhookWorker() {
        return new PaymentWebhookProcessingService(events, attempts, webhooks, recovery, pg, 20);
    }
    void start() { transactions.startConfirm(buyerId, order.paymentId(), request()); }
    ConfirmPaymentRequest request() { return new ConfirmPaymentRequest(order.paymentId(), "key", order.attemptId()); }
    TossPaymentResponse result(String status) { return "DONE".equals(status) ? cardApproval("key", order.pgOrderId(), 12000L)
            : new TossPaymentResponse("key", order.pgOrderId(), 12000L, status); }
    Long localOrderId() { return orderRepository.findIdByOrderNumberAndUserId(order.orderId(), buyerId).orElseThrow(); }
    void expireReservation() {
        jdbc.update("UPDATE orders SET reservation_expires_at=? WHERE order_number=?", LocalDateTime.now().minusMinutes(1), order.orderId());
    }
    void receiveWebhook() {
        webhooks.receive("tx-54", new TossPaymentWebhookRequest("PAYMENT_STATUS_CHANGED", "2026-10-01T12:00:00+09:00",
            new TossPaymentWebhookRequest.TossPaymentWebhookData("key", order.pgOrderId(), 12000L, 0L, "DONE")));
    }
    User user(String name) {
        return User.builder().nickname(name).email(name + "@example.com").authProvider(AuthProvider.KAKAO).providerUserId(name).build();
    }
}
