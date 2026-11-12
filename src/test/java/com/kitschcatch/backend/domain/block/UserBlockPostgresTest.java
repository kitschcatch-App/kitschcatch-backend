// 실제 PostgreSQL·HTTP·STOMP에서 차단의 권한과 저장·발송 경합을 검증한다.
package com.kitschcatch.backend.domain.block;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.kitschcatch.backend.domain.block.service.UserBlockService;
import com.kitschcatch.backend.domain.chat.entity.ChatRoom;
import com.kitschcatch.backend.domain.chat.repository.ChatRoomRepository;
import com.kitschcatch.backend.domain.chat.service.ChatMessageService;
import com.kitschcatch.backend.domain.chat.storage.ChatImageStorage;
import com.kitschcatch.backend.domain.notification.*;
import com.kitschcatch.backend.domain.post.entity.*;
import com.kitschcatch.backend.domain.post.repository.PostRepository;
import com.kitschcatch.backend.domain.user.entity.*;
import com.kitschcatch.backend.domain.user.repository.UserRepository;
import com.kitschcatch.backend.domain.user.service.UserWithdrawalService;
import com.kitschcatch.backend.global.security.JwtTokenProvider;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.converter.SimpleMessageConverter;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.stomp.*;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

@EnabledIfEnvironmentVariable(named = "ISSUE89_TEST_DB_URL", matches = "jdbc:postgresql:.*")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.datasource.driver-class-name=org.postgresql.Driver", "spring.jpa.hibernate.ddl-auto=create",
    "kakao.oauth.native-app-key=test-native-app-key", "app.orders.expiration-enabled=false",
    "app.payments.recovery.enabled=false", "app.notifications.push.scan-delay=1h"
})
@DirtiesContext
class UserBlockPostgresTest {
    private static final String SCHEMA = "issue89_" + UUID.randomUUID().toString().replace("-", "");
    private static final String URL = System.getenv("ISSUE89_TEST_DB_URL");
    private static final String USER = System.getenv().getOrDefault("ISSUE89_TEST_DB_USERNAME", "postgres");
    private static final String PASSWORD = System.getenv().getOrDefault("ISSUE89_TEST_DB_PASSWORD", "");
    private static final String MY_BLOCKS = "/api/users/me/blocks";

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) throws Exception {
        try (var connection = DriverManager.getConnection(URL, USER, PASSWORD); var sql = connection.createStatement()) {
            sql.execute("CREATE SCHEMA " + SCHEMA);
        }
        registry.add("spring.datasource.url", () -> URL);
        registry.add("spring.datasource.username", () -> USER);
        registry.add("spring.datasource.password", () -> PASSWORD);
        registry.add("spring.datasource.hikari.schema", () -> SCHEMA);
        registry.add("spring.jpa.properties.hibernate.default_schema", () -> SCHEMA);
    }

    @BeforeAll
    static void installMigration(@Autowired JdbcTemplate jdbc) throws Exception {
        jdbc.execute("DROP TABLE user_blocks");
        try (var connection = DriverManager.getConnection(URL, USER, PASSWORD); var sql = connection.createStatement();
            var file = UserBlockPostgresTest.class.getResourceAsStream("/db/manual/089_user_blocks.sql")) {
            connection.setSchema(SCHEMA);
            String migration = new String(Objects.requireNonNull(file).readAllBytes(), StandardCharsets.UTF_8);
            sql.execute(migration);
            sql.execute(migration);
        }
    }

    @AfterAll
    static void cleanSchema() throws Exception {
        try (var connection = DriverManager.getConnection(URL, USER, PASSWORD); var sql = connection.createStatement()) {
            sql.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
        }
    }

    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired UserRepository users;
    @Autowired PostRepository posts;
    @Autowired ChatRoomRepository rooms;
    @Autowired UserBlockService blocks;
    @Autowired ChatMessageService chat;
    @Autowired JwtTokenProvider jwt;
    @Autowired UserWithdrawalService withdrawal;
    @Autowired PlatformTransactionManager transactions;
    @Autowired DeviceTokenService devices;
    @Autowired NotificationService notifications;
    @Autowired PushProperties push;
    @Autowired PushWorker worker;
    @Autowired SimpMessagingTemplate realtime;
    @MockitoBean ChatImageStorage storage;
    @MockitoBean PushGateway gateway;
    User a, b, c;
    Post post;
    long room;
    String roomPath;

    @BeforeEach
    void setup() {
        push.setEnabled(false);
        for (String table : List.of("push_attempts", "push_deliveries", "notifications", "device_tokens",
            "chat_messages", "chat_rooms", "posts", "user_blocks", "user_follows", "users")) {
            jdbc.update("DELETE FROM " + table);
        }
        a = user(); b = user(); c = user();
        post = posts.saveAndFlush(Post.builder().user(b).title("검증 상품").description("테스트")
            .price(12000L).productCategory(ProductCategory.GOODS).productCondition(ProductCondition.NEW)
            .productStatus(ProductStatus.ON_SALE).build());
        room = rooms.saveAndFlush(ChatRoom.create(post, a, b)).getId();
        roomPath = "/api/chat-rooms/" + room;
    }

    @Test
    void directionControlsOnlyMyListAndMutualBlockRequiresBothRemovals() {
        assertThat(call("GET", MY_BLOCKS, null, null).status()).isEqualTo(401);
        assertThat(call("POST", blockPath(a), a, null).status()).isEqualTo(400);
        assertThat(call("POST", "/api/users/9223372036854775807/block", a, null).status()).isEqualTo(404);
        assertThat(call("DELETE", "/api/users/9223372036854775807/block", a, null).status()).isEqualTo(200);
        assertThat(call("GET", MY_BLOCKS + "?size=101", a, null).status()).isEqualTo(400);
        assertThat(call("GET", MY_BLOCKS + "?page=invalid", a, null).status()).isEqualTo(400);
        assertThat(call("POST", followPath(a), b, null).status()).isEqualTo(200);
        assertThat(call("POST", followPath(b), a, null).status()).isEqualTo(200);
        assertThat(call("POST", followPath(c), a, null).status()).isEqualTo(200);

        assertThat(call("POST", blockPath(b) + "?actorId=" + c.getId(), a, null).data())
            .containsEntry("blockedByMe", true);
        var first = jdbc.queryForMap("SELECT id, created_at FROM user_blocks");
        assertThat(call("POST", blockPath(b), a, null).status()).isEqualTo(200);
        assertThat(jdbc.queryForMap("SELECT id, created_at FROM user_blocks")).isEqualTo(first);
        assertThat(count("SELECT count(*) FROM user_follows")).isEqualTo(1);
        assertThat(call("GET", MY_BLOCKS, c, null).rows()).isEmpty();
        assertThat(call("GET", MY_BLOCKS + "?userId=" + a.getId(), b, null).rows()).isEmpty();
        assertThat(call("GET", MY_BLOCKS, a, null).rows().getFirst())
            .containsOnlyKeys("userId", "nickname", "profileImageUrl", "blockedAt")
            .containsEntry("nickname", "사용자");
        forbidden(call("POST", followPath(b), a, null));
        forbidden(call("POST", followPath(a), b, null));

        assertThat(call("POST", blockPath(a), b, null).status()).isEqualTo(200);
        assertThat(call("DELETE", blockPath(b), a, null).data()).containsEntry("blockedByMe", false);
        forbidden(call("POST", followPath(b), a, null));
        assertThat(call("DELETE", blockPath(b), a, null).status()).isEqualTo(200);
        assertThat(call("DELETE", blockPath(a), b, null).status()).isEqualTo(200);
        assertThat(count("SELECT count(*) FROM user_follows")).isEqualTo(1);
        assertThat(call("POST", followPath(b), a, null).status()).isEqualTo(200);
        assertThat(call("POST", blockPath(b), a, null).status()).isEqualTo(200);
        assertThat(jdbc.queryForMap("SELECT id, created_at FROM user_blocks")).isNotEqualTo(first);
        assertThat(call("POST", blockPath(c), a, null).status()).isEqualTo(200);
        var page = call("GET", MY_BLOCKS + "?size=1", a, null);
        assertThat(page.data()).containsEntry("totalElements", 2).containsEntry("totalPages", 2);
        assertThat(((Number) page.rows().getFirst().get("userId")).longValue()).isEqualTo(c.getId());
        assertThat(((Number) call("GET", MY_BLOCKS + "?page=1&size=1", a, null)
            .rows().getFirst().get("userId")).longValue()).isEqualTo(b.getId());
    }

    @Test
    void chatHistoryRemainsButBothDirectionsAndImagesAreRestricted() {
        assertThat(send(a, "이전 메시지").status()).isEqualTo(201);
        b.registerProfile("공개 이름", "공개 이름", null, Instant.now());
        users.saveAndFlush(b);
        blocks.change(a.getId(), b.getId(), true);
        assertThat(call("GET", "/api/users/" + b.getId(), a, null).data())
            .containsEntry("blockedByMe", true).containsEntry("canFollow", false);
        assertThat(call("GET", roomPath, a, null).data())
            .containsEntry("blockedByMe", true).containsEntry("canSendMessage", false);
        assertThat(call("GET", "/api/chat-rooms", b, null).items().getFirst())
            .containsEntry("blockedByMe", false).containsEntry("canSendMessage", false).containsEntry("unreadCount", 1);
        assertThat(call("GET", roomPath + "/messages", b, null).items().getFirst()).containsEntry("content", "이전 메시지");
        assertThat(call("GET", roomPath + "/messages", c, null).status()).isEqualTo(403);
        forbidden(call("POST", "/api/chat-rooms", a, Map.of("postId", post.getId())));
        for (var sender : List.of(a, b)) {
            forbidden(send(sender, "차단 이후"));
            forbidden(call("POST", roomPath + "/messages/images/upload-url", sender,
                Map.of("originalFileName", "photo.png", "contentType", "image/png")));
            forbidden(call("POST", roomPath + "/messages/images", sender, Map.of("objectKey", "test.png")));
        }
        verifyNoInteractions(storage);
        assertThat(count("SELECT count(*) FROM chat_messages")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM notifications")).isEqualTo(1);
        assertThat(call("POST", "/api/chat-rooms", c, Map.of("postId", post.getId())).status()).isEqualTo(201);
        blocks.change(a.getId(), b.getId(), false);
        assertThat(send(b, "해제 이후").status()).isEqualTo(201);
    }

    @Test
    void rechecksImageAttachmentAfterStorageLookup() throws Exception {
        String key = "chats/" + room + "/" + a.getId() + "/photo.png";
        when(storage.isOwnedChatImageKey(a.getId(), room, key)).thenReturn(true);
        when(storage.imageUrl(key)).thenReturn("https://images.example.test/photo.png");
        var checking = new CountDownLatch(1);
        var proceed = new CountDownLatch(1);
        when(storage.exists(key)).thenAnswer(invocation -> {
            checking.countDown();
            assertThat(proceed.await(10, TimeUnit.SECONDS)).isTrue();
            return true;
        });
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var pending = pool.submit(() -> call("POST", roomPath + "/messages/images", a, Map.of("objectKey", key)));
            try {
                assertThat(checking.await(10, TimeUnit.SECONDS)).isTrue();
                blocks.change(b.getId(), a.getId(), true);
            } finally {
                proceed.countDown();
            }
            forbidden(pending.get(10, TimeUnit.SECONDS));
        }
        assertThat(count("SELECT count(*) FROM chat_messages")).isZero();
    }

    @Test
    void blockCommitPrecedesWaitingMessageAndFollow() throws Exception {
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var results = new TransactionTemplate(transactions).execute(status -> {
                blocks.change(a.getId(), b.getId(), true);
                int backend = jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class);
                var message = pool.submit(() -> send(b, "잠금 이후"));
                var follow = pool.submit(() -> call("POST", followPath(a), b, null));
                awaitBlocking(backend, 2);
                return List.of(message, follow);
            });
            for (var result : results) forbidden(result.get(10, TimeUnit.SECONDS));
        }
        assertThat(count("SELECT count(*) FROM chat_messages")).isZero();
        assertThat(count("SELECT count(*) FROM user_follows")).isZero();
    }

    @Test
    void simultaneousOppositeBlocksPreserveBothDirections() throws Exception {
        var start = new CyclicBarrier(2);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = pool.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                return call("POST", blockPath(b), a, null);
            });
            var second = pool.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                return call("POST", blockPath(a), b, null);
            });
            assertThat(first.get(10, TimeUnit.SECONDS).status()).isEqualTo(200);
            assertThat(second.get(10, TimeUnit.SECONDS).status()).isEqualTo(200);
        }
        assertThat(count("SELECT count(*) FROM user_blocks")).isEqualTo(2);
    }

    @Test
    void committedMessageIsRetainedWhenBlockWaitsAndBlockRollbackRestoresState() throws Exception {
        devices.register(b.getId(), new DeviceTokenRequest("recipient-test", "ANDROID"));
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var waitingBlock = new TransactionTemplate(transactions).execute(status -> {
                chat.sendTextMessage(a.getId(), room, "차단보다 먼저 저장된 메시지");
                int backend = jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class);
                var pending = pool.submit(() -> call("POST", blockPath(a), b, null));
                awaitBlocking(backend, 1);
                return pending;
            });
            assertThat(waitingBlock.get(10, TimeUnit.SECONDS).status()).isEqualTo(200);
        }
        assertThat(count("SELECT count(*) FROM chat_messages")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM push_deliveries WHERE status='SKIPPED' AND last_code='USER_BLOCKED'"))
            .isEqualTo(1);

        blocks.change(b.getId(), a.getId(), false);
        call("POST", followPath(b), a, null);
        assertThat(send(a, "푸시 대기").status()).isEqualTo(201);
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            blocks.change(a.getId(), b.getId(), true);
            status.setRollbackOnly();
        });
        assertThat(count("SELECT count(*) FROM user_blocks")).isZero();
        assertThat(count("SELECT count(*) FROM user_follows")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM push_deliveries WHERE status='PENDING'")).isEqualTo(1);
    }

    @Test
    void workersSkipBlockedChatButKeepOtherRecipientsAndPaymentJobs() {
        devices.register(b.getId(), new DeviceTokenRequest("recipient-test", "ANDROID"));
        long otherRoom = rooms.saveAndFlush(ChatRoom.create(post, c, b)).getId();
        chat.sendTextMessage(a.getId(), room, "차단될 푸시");
        chat.sendTextMessage(c.getId(), otherRoom, "유지될 푸시");
        notifications.record(b.getId(), "payment:test", NotificationType.PAYMENT_SUCCESS, "ORD-TEST");
        jdbc.update("UPDATE push_deliveries SET next_attempt_at = CURRENT_TIMESTAMP + INTERVAL '1 hour' WHERE id = "
            + "(SELECT min(id) FROM push_deliveries)");
        blocks.change(a.getId(), b.getId(), true);
        assertThat(count("SELECT count(*) FROM push_deliveries WHERE status='SKIPPED'")).isEqualTo(1);
        notifications.record(b.getId(), "chat:legacy", NotificationType.CHAT_MESSAGE, Long.toString(room));
        when(gateway.send(any(), any())).thenReturn(new PushGateway.Result(true, false, false, "SENT", "test-id", 0));
        push.setEnabled(true);
        assertThat(worker.processNext()).isTrue();
        assertThat(worker.processNext()).isTrue();
        assertThat(worker.processNext()).isTrue();
        blocks.change(a.getId(), b.getId(), false);
        assertThat(worker.processNext()).isFalse();
        verify(gateway, times(2)).send(any(), any());
        assertThat(count("SELECT count(*) FROM push_deliveries WHERE status='SKIPPED' AND last_code='USER_BLOCKED'"))
            .isEqualTo(2);
    }

    @Test
    void blockWaitsForInFlightPushThenCancelsItsRetry() throws Exception {
        devices.register(b.getId(), new DeviceTokenRequest("recipient-test", "ANDROID"));
        chat.sendTextMessage(a.getId(), room, "진행 중인 푸시");
        var sending = new CompletableFuture<Integer>();
        var proceed = new CountDownLatch(1);
        when(gateway.send(any(), any())).thenAnswer(invocation -> {
            sending.complete(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
            assertThat(proceed.await(15, TimeUnit.SECONDS)).isTrue();
            return PushGateway.Result.retry("TEST_RETRY");
        });
        push.setEnabled(true);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var inFlight = pool.submit(worker::processNext);
            Future<Response> request;
            try {
                int backend = sending.get(10, TimeUnit.SECONDS);
                request = pool.submit(() -> call("POST", blockPath(b), a, null));
                awaitBlocking(backend, 1);
            } finally {
                proceed.countDown();
            }
            assertThat(inFlight.get(10, TimeUnit.SECONDS)).isTrue();
            assertThat(request.get(10, TimeUnit.SECONDS).status()).isEqualTo(200);
        }
        assertThat(count("SELECT count(*) FROM push_deliveries WHERE status='SKIPPED' AND last_code='USER_BLOCKED'"))
            .isEqualTo(1);
    }

    @Test
    void withdrawRemovesBothDirectionsWithoutDeletingChat() {
        blocks.change(a.getId(), b.getId(), true);
        blocks.change(b.getId(), a.getId(), true);
        blocks.change(c.getId(), b.getId(), true);
        withdrawal.withdraw(a.getId());
        assertThat(count("SELECT count(*) FROM user_blocks")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM chat_rooms")).isEqualTo(1);
        assertThat(call("POST", blockPath(a), b, null).status()).isEqualTo(404);
        assertThat(call("DELETE", blockPath(a), b, null).status()).isEqualTo(200);
    }

    @Test
    void openStompSubscriptionStopsDeliveringButReadStateAndOtherChatsStillWork() throws Exception {
        String sub = "/sub/chat-rooms/" + room;
        String pub = "/pub/chat-rooms/" + room;
        assertThat(send(a, "읽을 메시지").status()).isEqualTo(201);
        long otherRoom = rooms.saveAndFlush(ChatRoom.create(post, c, b)).getId();
        try (var socket = new SocketClient(b)) {
            var messages = socket.subscribe(sub);
            var reads = socket.subscribe(sub + "/read");
            var other = socket.subscribe("/sub/chat-rooms/" + otherRoom);
            probe(sub, messages);
            probe(sub + "/read", reads);
            probe("/sub/chat-rooms/" + otherRoom, other);

            blocks.change(a.getId(), b.getId(), true);
            socket.send(pub + "/messages/text", "{\"content\":\"전송 금지\"}");
            socket.send(pub + "/read", "{}");
            await().atMost(Duration.ofSeconds(5)).until(() -> count("SELECT count(*) FROM chat_messages WHERE is_read") == 1);
            realtime.convertAndSend(sub, "차단 전 대기 중이던 메시지");
            assertThat(messages.poll(500, TimeUnit.MILLISECONDS)).isNull();
            assertThat(reads.poll(500, TimeUnit.MILLISECONDS)).isNull();
            assertThat(count("SELECT count(*) FROM chat_messages")).isEqualTo(1);
            probe("/sub/chat-rooms/" + otherRoom, other);
            blocks.change(a.getId(), b.getId(), false);
            socket.send(pub + "/messages/text", "{\"content\":\"차단 해제 후 전송\"}");
            assertThat(messages.poll(5, TimeUnit.SECONDS)).contains("차단 해제 후 전송");
        }
    }

    private void probe(String destination, BlockingQueue<String> received) {
        await().atMost(Duration.ofSeconds(5)).until(() -> {
            realtime.convertAndSend(destination, "probe");
            return "probe".equals(received.poll(150, TimeUnit.MILLISECONDS));
        });
        received.clear();
    }

    private final class SocketClient implements AutoCloseable {
        private final WebSocketStompClient client = new WebSocketStompClient(new StandardWebSocketClient());
        private final StompSession session;

        SocketClient(User user) throws Exception {
            client.setMessageConverter(new SimpleMessageConverter());
            var connect = new StompHeaders();
            connect.set(HttpHeaders.AUTHORIZATION, "Bearer " + jwt.createAccessToken(user.getId()));
            session = client.connectAsync("ws://127.0.0.1:" + port + "/ws/websocket",
                new WebSocketHttpHeaders(), connect, new StompSessionHandlerAdapter() {}).get(10, TimeUnit.SECONDS);
        }

        BlockingQueue<String> subscribe(String destination) {
            var queue = new LinkedBlockingQueue<String>();
            session.subscribe(destination, new StompFrameHandler() {
                public Type getPayloadType(StompHeaders headers) { return byte[].class; }
                public void handleFrame(StompHeaders headers, Object payload) {
                    queue.add(new String((byte[]) payload, StandardCharsets.UTF_8));
                }
            });
            return queue;
        }

        void send(String destination, String body) {
            var headers = new StompHeaders();
            headers.setDestination(destination);
            headers.setContentType(MediaType.APPLICATION_JSON);
            session.send(headers, body.getBytes(StandardCharsets.UTF_8));
        }

        @Override public void close() {
            if (session.isConnected()) session.disconnect();
            client.stop();
        }
    }

    private void awaitBlocking(int backend, int expected) {
        await().atMost(Duration.ofSeconds(8)).until(() -> jdbc.queryForObject(
            """
            WITH RECURSIVE waiting(pid) AS (
                SELECT CAST(? AS integer)
                UNION
                SELECT a.pid FROM pg_stat_activity a
                JOIN waiting w ON w.pid = ANY(pg_blocking_pids(a.pid))
            )
            SELECT count(*) - 1 FROM waiting
            """, Integer.class, backend) >= expected);
    }

    private User user() {
        String id = UUID.randomUUID().toString();
        return users.saveAndFlush(User.builder().nickname("kakao-" + id).email(id + "@test.invalid")
            .authProvider(AuthProvider.KAKAO).providerUserId(id).build());
    }

    private int count(String query) { return jdbc.queryForObject(query, Integer.class); }
    private String blockPath(User user) { return "/api/users/" + user.getId() + "/block"; }
    private String followPath(User user) { return "/api/users/" + user.getId() + "/follow"; }
    private Response send(User user, String content) {
        return call("POST", roomPath + "/messages/text", user, Map.of("content", content));
    }

    private Response call(String method, String path, User user, Object body) {
        var request = RestClient.create("http://127.0.0.1:" + port).method(HttpMethod.valueOf(method)).uri(path);
        if (user != null) request.header(HttpHeaders.AUTHORIZATION, "Bearer " + jwt.createAccessToken(user.getId()));
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).body(body);
        return request.exchange((req, res) -> new Response(res.getStatusCode().value(), res.bodyTo(Map.class)));
    }

    private void forbidden(Response response) {
        assertThat(response.status()).as("%s", response.body()).isEqualTo(403);
        assertThat((Map<String, Object>) response.body().get("error")).containsEntry("code", "USER_009");
    }

    @SuppressWarnings("unchecked")
    record Response(int status, Map<String, Object> body) {
        Map<String, Object> data() { return (Map<String, Object>) body.get("data"); }
        List<Map<String, Object>> rows() { return (List<Map<String, Object>>) data().get("content"); }
        List<Map<String, Object>> items() { return (List<Map<String, Object>>) body.get("data"); }
    }
}
