// 전용 PostgreSQL에서 실제 HTTP·이벤트·SQL·잠금·재시도와 소유권을 검증한다.
package com.kitschcatch.backend.domain.notification;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.kitschcatch.backend.domain.chat.entity.*;
import com.kitschcatch.backend.domain.chat.repository.ChatRoomRepository;
import com.kitschcatch.backend.domain.chat.service.*;
import com.kitschcatch.backend.domain.order.entity.*;
import com.kitschcatch.backend.domain.order.repository.*;
import com.kitschcatch.backend.domain.order.service.*;
import com.kitschcatch.backend.domain.order.toss.TossPaymentResponse;
import com.kitschcatch.backend.domain.post.entity.*;
import com.kitschcatch.backend.domain.post.repository.PostRepository;
import com.kitschcatch.backend.domain.user.entity.*;
import com.kitschcatch.backend.domain.user.repository.UserRepository;
import com.kitschcatch.backend.global.security.JwtTokenProvider;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.*;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;

@EnabledIfEnvironmentVariable(named = "ISSUE50_TEST_DB_URL", matches = "jdbc:postgresql:.*")
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.datasource.driver-class-name=org.postgresql.Driver",
      "spring.jpa.hibernate.ddl-auto=create",
      "kakao.oauth.native-app-key=test-native-app-key",
      "app.orders.expiration-enabled=false",
      "app.payments.recovery.enabled=false",
      "springdoc.api-docs.enabled=true",
      "app.notifications.push.scan-delay=1h"
    })
@DirtiesContext
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NotificationPostgresTest {
  static final String schema = "issue50_" + UUID.randomUUID().toString().replace("-", "");
  static final String url = System.getenv("ISSUE50_TEST_DB_URL");
  static final String username =
      System.getenv().getOrDefault("ISSUE50_TEST_DB_USERNAME", "issue50");

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) throws Exception {
    try (var c = DriverManager.getConnection(url, username, "");
        var s = c.createStatement()) {
      s.execute("CREATE SCHEMA " + schema);
    }
    r.add("spring.datasource.url", () -> url);
    r.add("spring.datasource.username", () -> username);
    r.add("spring.datasource.password", () -> "");
    r.add("spring.datasource.hikari.schema", () -> schema);
    r.add("spring.jpa.properties.hibernate.default_schema", () -> schema);
  }

  @LocalServerPort int port;
  @Autowired JdbcTemplate jdbc;
  @Autowired UserRepository users;
  @Autowired PostRepository posts;
  @Autowired ChatRoomRepository rooms;
  @Autowired ChatMessageService chat;
  @Autowired ChatMessageCommandService images;
  @Autowired PurchaseOrderRepository orders;
  @Autowired PaymentRepository payments;
  @Autowired PaymentAttemptRepository paymentAttempts;
  @Autowired PaymentTransactionService paymentTransactions;
  @Autowired PaymentRecoveryService recovery;
  @Autowired ApplicationEventPublisher events;
  @Autowired NotificationService notifications;
  @Autowired DeviceTokenService devices;
  @Autowired PushWorker worker;
  @Autowired PushProperties push;
  @Autowired JwtTokenProvider jwt;
  @Autowired PlatformTransactionManager transactionManager;
  @MockitoBean PushGateway gateway;
  User buyer, seller, outsider;

  @BeforeAll
  void migrateTwiceFromManualSql() throws Exception {
    jdbc.execute("DROP TABLE push_attempts,push_deliveries,notifications,device_tokens");
    try (var c = DriverManager.getConnection(url, username, "");
        var s = c.createStatement();
        var stream = getClass().getResourceAsStream("/db/manual/050_notifications.sql")) {
      c.setSchema(schema);
      String sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
      s.execute(sql);
      s.execute(sql);
    }
  }

  @AfterAll
  static void cleanup() throws Exception {
    try (var c = DriverManager.getConnection(url, username, "");
        var s = c.createStatement()) {
      s.execute("DROP SCHEMA " + schema + " CASCADE");
    }
  }

  @BeforeEach
  void resetData() {
    push.setEnabled(false);
    reset(gateway);
    for (String table :
        List.of(
            "push_attempts",
            "push_deliveries",
            "notifications",
            "device_tokens",
            "chat_messages",
            "chat_rooms",
            "payment_webhook_events",
            "payment_attempts",
            "payments",
            "orders",
            "post_images",
            "posts",
            "users")) jdbc.update("DELETE FROM " + table);
    buyer = user("구매자");
    seller = user("판매자");
    outsider = user("외부인");
  }

  User user(String nickname) {
    String id = UUID.randomUUID().toString();
    return users.saveAndFlush(
        User.builder()
            .nickname(nickname)
            .email(id + "@test.invalid")
            .authProvider(AuthProvider.KAKAO)
            .providerUserId(id)
            .build());
  }

  record Response(int status, Map<String, Object> body) {
    Map<String, Object> data() {
      return (Map<String, Object>) body.get("data");
    }
  }

  Response call(HttpMethod method, String path, User user, Object body) {
    var request = RestClient.create("http://127.0.0.1:" + port).method(method).uri(path);
    if (user != null)
      request.header(HttpHeaders.AUTHORIZATION, "Bearer " + jwt.createAccessToken(user.getId()));
    if (body != null) request.contentType(MediaType.APPLICATION_JSON).body(body);
    return request.exchange(
        (req, res) -> new Response(res.getStatusCode().value(), res.bodyTo(Map.class)));
  }

  @Test
  void httpRequiresAuthenticationAndValidInputs() {
    for (var endpoint :
        List.of(
            Map.entry(HttpMethod.POST, "/api/users/me/device-tokens"),
            Map.entry(HttpMethod.DELETE, "/api/users/me/device-tokens"),
            Map.entry(HttpMethod.GET, "/api/notifications"),
            Map.entry(HttpMethod.PATCH, "/api/notifications/1/read"),
            Map.entry(HttpMethod.PATCH, "/api/notifications/read-all"))) {
      assertThat(
              call(
                      endpoint.getKey(),
                      endpoint.getValue(),
                      null,
                      Map.of("token", "test", "platform", "IOS"))
                  .status())
          .isEqualTo(401);
    }
    for (var body :
        List.of(
            Map.of("token", " ", "platform", "IOS"),
            Map.of("token", "valid", "platform", "OTHER"),
            Map.of("token", "x".repeat(2049), "platform", "IOS"),
            Map.of("token", "x/y", "platform", "IOS")))
      assertThat(call(HttpMethod.POST, "/api/users/me/device-tokens", buyer, body).status())
          .isEqualTo(400);
    for (var query : List.of("page=-1", "page=10001", "size=0", "size=101", "page=x", "size=x"))
      assertThat(call(HttpMethod.GET, "/api/notifications?" + query, buyer, null).status())
          .isEqualTo(400);
    assertThat(call(HttpMethod.PATCH, "/api/notifications/-1/read", buyer, null).status())
        .isEqualTo(400);
    assertThat(call(HttpMethod.PATCH, "/api/notifications/x/read", buyer, null).status())
        .isEqualTo(400);
    assertThat(call(HttpMethod.GET, "/api/notifications", buyer, null).data())
        .containsEntry("totalElements", 0)
        .containsEntry("unreadCount", 0);
  }

  @Test
  void tokensTransferAndOldAccountCannotDeleteCurrentOwnership() {
    var body = Map.of("token", "synthetic-device", "platform", "IOS");
    assertThat(call(HttpMethod.POST, "/api/users/me/device-tokens", buyer, body).status())
        .isEqualTo(200);
    call(HttpMethod.POST, "/api/users/me/device-tokens", buyer, body);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM device_tokens", Integer.class))
        .isEqualTo(1);
    assertThat(jdbc.queryForObject("SELECT ownership_version FROM device_tokens", Long.class))
        .isZero();
    call(HttpMethod.POST, "/api/users/me/device-tokens", seller, body);
    call(
        HttpMethod.DELETE,
        "/api/users/me/device-tokens",
        buyer,
        Map.of("token", "synthetic-device"));
    assertThat(jdbc.queryForObject("SELECT user_id FROM device_tokens", Long.class))
        .isEqualTo(seller.getId());
    assertThat(jdbc.queryForObject("SELECT active FROM device_tokens", Boolean.class)).isTrue();
    call(
        HttpMethod.DELETE,
        "/api/users/me/device-tokens",
        seller,
        Map.of("token", "synthetic-device"));
    call(
        HttpMethod.DELETE,
        "/api/users/me/device-tokens",
        seller,
        Map.of("token", "synthetic-device"));
    assertThat(jdbc.queryForObject("SELECT active FROM device_tokens", Boolean.class)).isFalse();
  }

  @Test
  void inboxDedupePagesAndReadAreOwnerScopedAndIdempotent() {
    notifications.record(buyer.getId(), "event:1", NotificationType.CHAT_MESSAGE, "1");
    notifications.record(buyer.getId(), "event:1", NotificationType.CHAT_MESSAGE, "1");
    notifications.record(buyer.getId(), "event:2", NotificationType.PAYMENT_SUCCESS, "ORD-TEST");
    notifications.record(seller.getId(), "event:3", NotificationType.PAYMENT_SUCCESS, "ORD-OTHER");
    jdbc.update("UPDATE notifications SET created_at='2026-10-01 10:00:00'");
    var response = call(HttpMethod.GET, "/api/notifications?size=1", buyer, null);
    assertThat(response.data())
        .containsEntry("totalElements", 2)
        .containsEntry("totalPages", 2)
        .containsEntry("unreadCount", 2);
    var items = (List<Map<String, Object>>) response.data().get("notifications");
    long id = ((Number) items.getFirst().get("id")).longValue();
    assertThat(items.getFirst()).containsEntry("type", "PAYMENT_SUCCESS");
    assertThat(call(HttpMethod.PATCH, "/api/notifications/" + id + "/read", seller, null).status())
        .isEqualTo(404);
    assertThat(call(HttpMethod.PATCH, "/api/notifications/999999/read", buyer, null).status())
        .isEqualTo(404);
    assertThat(call(HttpMethod.PATCH, "/api/notifications/" + id + "/read", buyer, null).status())
        .isEqualTo(200);
    var readAt =
        jdbc.queryForObject("SELECT read_at FROM notifications WHERE id=?", Timestamp.class, id);
    call(HttpMethod.PATCH, "/api/notifications/" + id + "/read", buyer, null);
    assertThat(
            jdbc.queryForObject(
                "SELECT read_at FROM notifications WHERE id=?", Timestamp.class, id))
        .isEqualTo(readAt);
    assertThat(call(HttpMethod.PATCH, "/api/notifications/read-all", buyer, null).data())
        .containsEntry("updatedCount", 1);
    assertThat(call(HttpMethod.PATCH, "/api/notifications/read-all", buyer, null).data())
        .containsEntry("updatedCount", 0);
    assertThat(notifications.list(seller.getId(), 0, 20).unreadCount()).isEqualTo(1);
  }

  Post post() {
    return posts.saveAndFlush(
        Post.builder()
            .user(seller)
            .title("테스트 상품")
            .description("설명")
            .price(1000L)
            .productCategory(ProductCategory.values()[0])
            .productStatus(ProductStatus.ON_SALE)
            .productCondition(ProductCondition.values()[0])
            .build());
  }

  @Test
  void realTextAndImageMessageTransactionsCreateOnlyRecipientNotificationsAndRollbackTogether() {
    var room = rooms.saveAndFlush(ChatRoom.create(post(), buyer, seller));
    devices.register(seller.getId(), new DeviceTokenRequest("synthetic-seller", "IOS"));
    var message = chat.sendTextMessage(buyer.getId(), room.getId(), "개인 메시지");
    images.saveImageMessage(seller.getId(), room.getId(), "https://test.invalid/image");
    assertThat(notifications.list(seller.getId(), 0, 20).notifications()).hasSize(1);
    assertThat(notifications.list(buyer.getId(), 0, 20).notifications()).hasSize(1);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM push_deliveries", Integer.class))
        .isEqualTo(1);
    assertThatThrownBy(
            () ->
                new TransactionTemplate(transactionManager)
                    .execute(
                        status -> {
                          chat.sendTextMessage(buyer.getId(), room.getId(), "롤백");
                          throw new IllegalStateException();
                        }))
        .isInstanceOf(IllegalStateException.class);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM notifications", Integer.class))
        .isEqualTo(2);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM chat_messages", Integer.class))
        .isEqualTo(2);
    verifyNoInteractions(gateway);
  }

  Payment payment() {
    var post = post();
    String number = "ORD-" + UUID.randomUUID().toString().replace("-", "").toUpperCase();
    post.reserve(number);
    posts.saveAndFlush(post);
    var order =
        orders.saveAndFlush(
            PurchaseOrder.builder()
                .orderNumber(number)
                .user(buyer)
                .post(post)
                .amount(1000L)
                .pgProvider(PgProvider.TOSS_PAYMENTS)
                .orderStatus(OrderStatus.PENDING)
                .reservationExpiresAt(LocalDateTime.now().plusMinutes(10))
                .build());
    var payment =
        Payment.builder()
            .paymentId("PAY-" + UUID.randomUUID())
            .order(order)
            .amount(1000L)
            .paymentMethod(PaymentMethod.CARD)
            .paymentStatus(PaymentStatus.PROCESSING)
            .paymentKey("synthetic-key")
            .build();
    return payments.saveAndFlush(payment);
  }

  @Test
  void normalPaymentAndRecoveryEventsNotifyBothSnapshotParticipantsWithoutDuplicates() {
    var payment = payment();
    paymentTransactions.completeConfirm(buyer.getId(), payment.getPaymentId(), "synthetic-key");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM notifications WHERE type='PAYMENT_SUCCESS'", Integer.class))
        .isEqualTo(2);
    paymentTransactions.startCancel(buyer.getId(), payment.getPaymentId());
    paymentTransactions.completeCancel(buyer.getId(), payment.getPaymentId());
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM notifications WHERE type='PAYMENT_CANCELED'", Integer.class))
        .isEqualTo(2);
    var recovered = payment();
    var attempt =
        paymentAttempts.saveAndFlush(
            PaymentAttempt.builder()
                .attemptId("ATT-SYNTHETIC")
                .payment(recovered)
                .sequenceNumber(1)
                .operation(PaymentAttemptOperation.CONFIRM)
                .attemptStatus(PaymentAttemptStatus.PROCESSING)
                .pgOrderId(recovered.getOrder().getOrderNumber())
                .paymentKey("synthetic-key")
                .amount(1000L)
                .pgIdempotencyKey("synthetic")
                .requestedAt(LocalDateTime.now())
                .nextCheckAt(LocalDateTime.now())
                .build());
    var done =
        new TossPaymentResponse(
            "synthetic-key",
            recovered.getOrder().getOrderNumber(),
            1000L,
            "DONE",
            1000L,
            null,
            null,
            null);
    recovery.recover(attempt.getAttemptId(), done);
    recovery.recover(attempt.getAttemptId(), done);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM notifications WHERE type='PAYMENT_SUCCESS'", Integer.class))
        .isEqualTo(4);
    recovery.recover(
        attempt.getAttemptId(),
        new TossPaymentResponse(
            "synthetic-key",
            recovered.getOrder().getOrderNumber(),
            1000L,
            "CANCELED",
            0L,
            null,
            null,
            null));
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM notifications WHERE type='PAYMENT_CANCELED'", Integer.class))
        .isEqualTo(4);
  }

  @Test
  void workerIsDisabledAndSkipsOldOwnershipIncludingTransferBack() {
    devices.register(buyer.getId(), new DeviceTokenRequest("synthetic", "IOS"));
    notifications.record(buyer.getId(), "event", NotificationType.CHAT_MESSAGE, "1");
    assertThat(worker.processNext()).isFalse();
    verifyNoInteractions(gateway);
    devices.register(seller.getId(), new DeviceTokenRequest("synthetic", "IOS"));
    devices.register(buyer.getId(), new DeviceTokenRequest("synthetic", "IOS"));
    push.setEnabled(true);
    assertThat(worker.processNext()).isTrue();
    assertThat(jdbc.queryForObject("SELECT status FROM push_deliveries", String.class))
        .isEqualTo("SKIPPED");
    verifyNoInteractions(gateway);
  }

  @Test
  void workerRetriesPersistsAttemptsStopsAtFiveAndDeactivatesOnlyInvalidToken() {
    devices.register(buyer.getId(), new DeviceTokenRequest("synthetic", "ANDROID"));
    notifications.record(buyer.getId(), "event", NotificationType.CHAT_MESSAGE, "1");
    push.setEnabled(true);
    when(gateway.send(any(), any())).thenReturn(PushGateway.Result.retry("HTTP_503"));
    for (int i = 0; i < 5; i++) {
      jdbc.update("UPDATE push_deliveries SET next_attempt_at=CURRENT_TIMESTAMP");
      assertThat(worker.processNext()).isTrue();
      if (i == 0) assertThat(worker.processNext()).isFalse();
    }
    assertThat(jdbc.queryForObject("SELECT status FROM push_deliveries", String.class))
        .isEqualTo("FAILED");
    assertThat(jdbc.queryForObject("SELECT count(*) FROM push_attempts", Integer.class))
        .isEqualTo(5);
    notifications.record(buyer.getId(), "event2", NotificationType.CHAT_MESSAGE, "1");
    when(gateway.send(any(), any()))
        .thenReturn(new PushGateway.Result(false, false, true, "UNREGISTERED", null, 0));
    worker.processNext();
    assertThat(jdbc.queryForObject("SELECT active FROM device_tokens", Boolean.class)).isFalse();
  }

  @Test
  void concurrentTokenRegistrationAndEventReplayProduceSingleRows() throws Exception {
    parallel(() -> devices.register(buyer.getId(), new DeviceTokenRequest("race-token", "IOS")));
    assertThat(jdbc.queryForObject("SELECT count(*) FROM device_tokens", Integer.class))
        .isEqualTo(1);
    parallel(
        () ->
            notifications.record(buyer.getId(), "race-event", NotificationType.CHAT_MESSAGE, "1"));
    assertThat(jdbc.queryForObject("SELECT count(*) FROM notifications", Integer.class))
        .isEqualTo(1);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM push_deliveries", Integer.class))
        .isEqualTo(1);
  }

  void parallel(Runnable action) throws Exception {
    try (var executor = Executors.newFixedThreadPool(4)) {
      var start = new CountDownLatch(1);
      var results = new ArrayList<Future<?>>();
      for (int i = 0; i < 4; i++)
        results.add(
            executor.submit(
                () -> {
                  try {
                    start.await();
                    action.run();
                  } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                  }
                }));
      start.countDown();
      for (var result : results) result.get(15, TimeUnit.SECONDS);
    }
  }

  @Test
  void concurrentWorkersSkipLockedAndOwnershipTransferWaitsForInFlightDelivery() throws Exception {
    devices.register(buyer.getId(), new DeviceTokenRequest("worker-token", "IOS"));
    notifications.record(buyer.getId(), "event", NotificationType.CHAT_MESSAGE, "1");
    push.setEnabled(true);
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    when(gateway.send(any(), any()))
        .thenAnswer(
            invocation -> {
              entered.countDown();
              assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
              return new PushGateway.Result(
                  true, false, false, "OK", "projects/test/messages/1", 0);
            });
    try (var executor = Executors.newFixedThreadPool(3)) {
      var first = executor.submit(() -> worker.processNext());
      assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
      assertThat(executor.submit(() -> worker.processNext()).get(5, TimeUnit.SECONDS)).isFalse();
      var transfer =
          executor.submit(
              () ->
                  devices.register(seller.getId(), new DeviceTokenRequest("worker-token", "IOS")));
      assertThatThrownBy(() -> transfer.get(200, TimeUnit.MILLISECONDS))
          .isInstanceOf(TimeoutException.class);
      release.countDown();
      assertThat(first.get(10, TimeUnit.SECONDS)).isTrue();
      transfer.get(10, TimeUnit.SECONDS);
    } finally {
      release.countDown();
    }
    verify(gateway, times(1)).send(any(), any());
    assertThat(jdbc.queryForObject("SELECT status FROM push_deliveries", String.class))
        .isEqualTo("SENT");
  }
}
