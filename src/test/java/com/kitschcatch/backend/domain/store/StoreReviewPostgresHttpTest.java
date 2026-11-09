// 실제 PostgreSQL 스키마와 HTTP에서 매장 리뷰 권한, 집계와 동시성 계약을 검증한다.
package com.kitschcatch.backend.domain.store;

import static com.kitschcatch.backend.support.S3GetUrlAssertions.assertGetUrl;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.kitschcatch.backend.domain.store.entity.Store;
import com.kitschcatch.backend.domain.store.repository.StoreRepository;
import com.kitschcatch.backend.domain.user.entity.AuthProvider;
import com.kitschcatch.backend.domain.user.entity.User;
import com.kitschcatch.backend.domain.user.repository.UserRepository;
import com.kitschcatch.backend.global.security.JwtTokenProvider;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.web.client.RestClient;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

@EnabledIfEnvironmentVariable(named = "ISSUE85_TEST_DB_URL", matches = "jdbc:postgresql:.*")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.datasource.driver-class-name=org.postgresql.Driver", "spring.jpa.hibernate.ddl-auto=create",
    "kakao.oauth.native-app-key=test", "app.orders.expiration-enabled=false",
    "app.payments.recovery.enabled=false", "app.s3.bucket=review-test-bucket",
    "app.s3.download-url-ttl=12m", "springdoc.api-docs.enabled=true"
})
@DirtiesContext
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StoreReviewPostgresHttpTest {
    private static PostgresStoreTestDatabase database;

    @LocalServerPort int port;
    @Autowired StoreRepository stores;
    @Autowired UserRepository users;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtTokenProvider jwt;
    @TestBean(methodName = "testPresigner") S3Presigner presigner;

    private RestClient http;
    private long storeId;
    private User author;
    private User reader;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) throws Exception {
        database = new PostgresStoreTestDatabase("85");
        registry.add("spring.datasource.url", () -> database.url);
        registry.add("spring.datasource.username", () -> database.username);
        registry.add("spring.datasource.password", () -> database.password);
        registry.add("spring.datasource.hikari.schema", () -> database.schema);
        registry.add("spring.jpa.properties.hibernate.default_schema", () -> database.schema);
    }

    static S3Presigner testPresigner() {
        return StoreHttpContract.testPresigner();
    }

    @BeforeAll
    static void applyManualSql() throws Exception {
        try (var connection = database.connect(); var statement = connection.createStatement()) {
            statement.execute("DROP TABLE store_review_images, store_reviews");
            for (String file : List.of("091_reports.sql", "107_report_reviews.sql")) {
                try (var input = StoreReviewPostgresHttpTest.class.getResourceAsStream("/db/manual/" + file)) {
                    statement.execute(new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
                }
            }
            PostgresStoreTestDatabase.migrateReviews(connection);
            PostgresStoreTestDatabase.migrateReviewImages(connection);
            PostgresStoreTestDatabase.migrateReviews(connection);
            PostgresStoreTestDatabase.migrateReviewImages(connection);
        }
    }

    @AfterAll
    static void dropSchema() throws Exception {
        if (database != null) {
            database.close();
        }
    }

    @BeforeEach
    void fixtures() {
        var factory = new JdkClientHttpRequestFactory();
        factory.setReadTimeout(java.time.Duration.ofSeconds(15));
        http = RestClient.builder().baseUrl("http://127.0.0.1:" + port).requestFactory(factory).build();
        storeId = stores.saveAndFlush(Store.builder().name("리뷰 검증 매장").region("서울").address("검증 주소")
            .latitude(37.5).longitude(127).build()).getId();
        author = user();
        reader = user();
    }

    @Test
    void moderationRequiresBothGrantsAndRestoresOnlyExistingContent() {
        var moderator = user();
        var created = call("POST", reviewsPath(), author, input(5));
        long id = created.id();
        long report = jdbc.queryForObject("""
            insert into reports(reporter_id,target_type,target_id,target_user_id,reason_code,description,
                request_fingerprint,message_ids,target_snapshot,status,created_at,expires_at)
            values (?,'STORE_REVIEW',?,?,'SPAM','검증','hash','[]','{}','IN_REVIEW',now(),now()+interval '1 day')
            returning id
            """, Long.class, reader.getId(), id, author.getId());
        String path = "/api/admin/store-reviews/" + id;
        jdbc.update("insert into report_reviewers(user_id,granted_at) values (?,now())", moderator.getId());
        assertThat(call("GET", path, moderator, null).status()).isEqualTo(403);
        jdbc.update("insert into store_review_moderators(user_id) values (?)", moderator.getId());
        var command = Map.of("action", "HIDE", "reportId", report, "reason", "신고 확인",
            "requestId", UUID.randomUUID().toString(), "expectedVersion", created.data().get("version"));
        assertThat(call("PATCH", path + "/moderation", moderator, command).status()).isEqualTo(200);
        assertThat(call("PATCH", path + "/moderation", moderator, command).data()).containsEntry("replayed", true);
        assertThat(call("GET", reviewPath(id), reader, null).status()).isEqualTo(404);
        assertThat(call("GET", reviewsPath() + "/summary", reader, null).data()).containsEntry("reviewCount", 0);
        assertThat(call("PATCH", reviewPath(id), author, Map.of("rating", 4, "version", 1)).status()).isEqualTo(409);
        assertThat(call("DELETE", reviewPath(id), author, null).status()).isEqualTo(200);
        jdbc.update("delete from reports where id=?", report);
        var restore = Map.of("action", "RESTORE", "reason", "제재 해제", "requestId", UUID.randomUUID().toString(),
            "expectedVersion", 2);
        assertThat(call("PATCH", path + "/moderation", moderator, restore).status()).isEqualTo(200);
        assertThat(call("GET", reviewPath(id), reader, null).status()).isEqualTo(404);
        assertThat(jdbc.queryForObject("select count(*) from store_review_moderation_history where review_id=?", Long.class, id))
            .isEqualTo(2);
        jdbc.update("update store_review_moderators set revoked_at=now() where user_id=?", moderator.getId());
        assertThat(call("GET", path, moderator, null).status()).isEqualTo(403);
    }

    @Test
    void createsReadsUpdatesDeletesAndAllowsRewriting() {
        var mine = call("GET", reviewsPath() + "/me", author, null);
        assertThat(mine.status()).isEqualTo(200);
        assertThat(mine.data()).containsEntry("review", null);

        String imageKey = "profiles/" + author.getId() + "/a818e030-3f69-431a-8d21-d69d1a600cd6.jpg";
        jdbc.update("UPDATE users SET profile_image_key = ? WHERE id = ?", imageKey, author.getId());
        var created = call("POST", reviewsPath(), author, Map.of("rating", 5, "content", "  매장이 좋아요. \n"));
        assertThat(created.status()).isEqualTo(201);
        assertThat(created.data()).containsEntry("content", "매장이 좋아요.").containsEntry("mine", true)
            .containsEntry("hidden", false).containsEntry("version", 0).containsEntry("images", List.of());
        Instant.parse((String) created.data().get("createdAt"));
        Map<?, ?> publicAuthor = (Map<?, ?>) created.data().get("author");
        assertThat(publicAuthor.keySet().stream().map(Object::toString).toList())
            .containsExactlyInAnyOrder("id", "nickname", "profileImageUrl");
        assertGetUrl((String) publicAuthor.get("profileImageUrl"), "review-test-bucket", imageKey, 720,
            "test-access", "test-secret");

        String path = reviewPath(created.id());
        var detail = call("GET", path, reader, null);
        assertThat(detail.status()).isEqualTo(200);
        assertThat(detail.data()).containsEntry("mine", false);
        assertThat(call("GET", reviewsPath() + "/me", author, null).data().get("review")).isInstanceOf(Map.class);
        assertError(call("POST", reviewsPath(), author, input(3)), 409, "STORE_REVIEW_002");
        assertError(call("PATCH", path, reader, Map.of("version", 0, "rating", 3)), 403, "STORE_REVIEW_003");
        assertError(call("DELETE", path, reader, null), 403, "STORE_REVIEW_003");

        var updated = call("PATCH", path, author, Map.of("version", 0, "rating", 3));
        assertThat(updated.status()).isEqualTo(200);
        assertThat(updated.data()).containsEntry("rating", 3).containsEntry("version", 1)
            .containsEntry("content", "매장이 좋아요.").containsEntry("createdAt", created.data().get("createdAt"));
        assertError(call("PATCH", path, author, Map.of("version", 0, "rating", 2)), 409, "STORE_REVIEW_004");
        assertThat(call("GET", reviewsPath() + "/summary", reader, null).data().get("averageRating")).isEqualTo(3.0);

        assertThat(call("DELETE", path, author, null).status()).isEqualTo(200);
        assertThat(call("DELETE", path, author, null).status()).isEqualTo(200);
        assertError(call("GET", path, reader, null), 404, "STORE_REVIEW_001");
        assertThat(call("GET", reviewsPath() + "/me", author, null).data()).containsEntry("review", null);
        assertThat(call("GET", reviewsPath() + "/summary", reader, null).data())
            .containsEntry("reviewCount", 0).containsEntry("averageRating", null);
        var replacement = create(author, 2);
        assertThat(replacement.id()).isNotEqualTo(created.id());
    }

    @Test
    void paginationAndSummaryExcludeDeletedHiddenAndWithdrawnAuthors() {
        var first = create(author, 1);
        var second = create(reader, 2);
        var third = create(user(), 2);
        var hiddenAuthor = user();
        var hidden = create(hiddenAuthor, 5);
        var deletedAuthor = user();
        var deleted = create(deletedAuthor, 5);
        var withdrawnAuthor = user();
        var withdrawn = create(withdrawnAuthor, 5);

        jdbc.update("UPDATE store_reviews SET created_at = TIMESTAMPTZ '2026-01-01T00:00:00Z' WHERE store_id = ?", storeId);
        jdbc.update("UPDATE store_reviews SET hidden_at = CURRENT_TIMESTAMP WHERE id = ?", hidden.id());
        assertThat(call("DELETE", reviewPath(deleted.id()), deletedAuthor, null).status()).isEqualTo(200);
        assertThat(call("DELETE", "/api/users/me", withdrawnAuthor, null).status()).isEqualTo(200);

        var firstPage = call("GET", reviewsPath() + "?size=2", reader, null);
        assertThat(firstPage.data()).containsEntry("totalElements", 3).containsEntry("totalPages", 2);
        assertThat(firstPage.ids()).containsExactly(third.id(), second.id());
        assertThat(call("GET", reviewsPath() + "?size=2&page=1", reader, null).ids()).containsExactly(first.id());
        for (var excluded : List.of(hidden, deleted, withdrawn)) {
            assertError(call("GET", reviewPath(excluded.id()), reader, null), 404, "STORE_REVIEW_001");
        }
        assertError(call("POST", reviewsPath(), hiddenAuthor, input(3)), 409, "STORE_REVIEW_002");
        assertThat((Map<?, ?>) call("GET", reviewsPath() + "/me", hiddenAuthor, null).data().get("review"))
            .extracting(row -> row.get("hidden")).isEqualTo(true);
        assertThat(call("DELETE", reviewPath(hidden.id()), hiddenAuthor, null).status()).isEqualTo(200);
        assertError(call("POST", reviewsPath(), hiddenAuthor, input(3)), 409, "STORE_REVIEW_002");

        var summary = call("GET", reviewsPath() + "/summary", reader, null);
        assertThat(summary.data()).containsEntry("reviewCount", 3).containsEntry("visibleReviewCount", 3)
            .containsEntry("averageRating", 1.67)
            .containsEntry("ratingCounts", Map.of("1", 1, "2", 2, "3", 0, "4", 0, "5", 0));
        var detail = call("GET", "/api/stores/" + storeId, reader, null);
        assertThat(detail.data()).containsEntry("reviewSummary", Map.of("reviewCount", 3, "averageRating", 1.67));
    }

    @Test
    void validatesInputAndAuthentication() {
        assertThat(call("GET", reviewsPath(), null, null).status()).isEqualTo(401);
        assertThat(call("GET", reviewsPath() + "/summary", null, null).status()).isEqualTo(401);
        assertError(call("POST", "/api/stores/9223372036854775807/reviews", author, input(3)), 404, "STORE_001");
        assertError(call("GET", "/api/stores/0/reviews", author, null), 400, "COMMON_002");
        assertThat(call("GET", reviewsPath() + "?size=101", author, null).status()).isEqualTo(400);
        for (var rating : List.of(0, 6, 4.5)) {
            assertThat(call("POST", reviewsPath(), author, Map.of("rating", rating, "content", "본문")).status())
                .isEqualTo(400);
        }
        for (String content : List.of(" \t\n", "가".repeat(1001), "문" + (char) 0 + "자")) {
            assertThat(call("POST", reviewsPath(), author, Map.of("rating", 5, "content", content)).status())
                .isEqualTo(400);
        }
        var created = call("POST", reviewsPath(), author, Map.of("rating", 5, "content", " " + "가".repeat(1000) + " "));
        assertThat(created.status()).isEqualTo(201);
        assertThat(call("PATCH", reviewPath(created.id()), author, Map.of("rating", 5)).status()).isEqualTo(400);
        assertThat(call("PATCH", reviewPath(created.id()), author, Map.of("version", 0)).status()).isEqualTo(400);
    }

    @Test
    void limitsRepeatedCreationAfterDeletionWithinTwentyFourHours() {
        for (int i = 0; i < 5; i++) {
            assertThat(call("DELETE", reviewPath(create(author, 4).id()), author, null).status()).isEqualTo(200);
        }
        assertError(call("POST", reviewsPath(), author, input(4)), 429, "STORE_REVIEW_005");
        jdbc.update("UPDATE store_reviews SET created_at = CURRENT_TIMESTAMP - INTERVAL '25 hours' WHERE store_id = ?",
            storeId);
        create(author, 4);
        create(reader, 5);
    }

    @Test
    void simultaneousCreatesAndEditsHaveOneWinner() throws Exception {
        Callable<Result> create = () -> call("POST", reviewsPath(), author, input(5));
        var creations = race(create, create);
        assertThat(creations).extracting(Result::status).containsExactlyInAnyOrder(201, 409);
        String path = reviewPath(creations.stream().filter(result -> result.status() == 201).findFirst().orElseThrow().id());

        var updates = race(() -> call("PATCH", path, author, Map.of("rating", 2, "version", 0)),
            () -> call("PATCH", path, author, Map.of("rating", 4, "version", 0)));
        assertThat(updates).extracting(Result::status).containsExactlyInAnyOrder(200, 409);
        var winner = updates.stream().filter(result -> result.status() == 200).findFirst().orElseThrow();
        assertThat(call("GET", path, reader, null).data())
            .containsEntry("rating", winner.data().get("rating")).containsEntry("version", 1);
        assertThat(call("GET", reviewsPath() + "/summary", reader, null).data()).containsEntry("reviewCount", 1);
    }

    @Test
    void concurrentWithdrawalAndCreationCannotLeaveVisibleReviews() throws Exception {
        var results = race(() -> call("POST", reviewsPath(), author, input(5)),
            () -> call("DELETE", "/api/users/me", author, null));
        assertThat(results.get(0).status()).isIn(201, 401);
        assertThat(results.get(1).status()).isEqualTo(200);
        assertThat(call("GET", reviewsPath(), reader, null).ids()).isEmpty();
        assertThat(call("GET", reviewsPath() + "/summary", reader, null).data()).containsEntry("reviewCount", 0);
    }

    @Test
    void migrationCanBeReappliedAndDatabaseProtectsReviewData() throws Exception {
        var created = create(author, 4);
        try (var connection = database.connect()) {
            PostgresStoreTestDatabase.migrateReviews(connection);
            PostgresStoreTestDatabase.migrateReviewImages(connection);
        }
        assertThat(call("GET", reviewPath(created.id()), reader, null).data()).containsEntry("rating", 4);
        assertThatThrownBy(() -> jdbc.update("""
            INSERT INTO store_reviews (store_id, author_id, rating, content, created_at, updated_at)
            VALUES (?, ?, 3, '중복', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """, storeId, author.getId())).isInstanceOf(DataIntegrityViolationException.class)
            .hasMessageContaining("uk_store_reviews_creation_guard");
        assertThatThrownBy(() -> jdbc.update("UPDATE store_reviews SET rating = 6 WHERE id = ?", created.id()))
            .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("ck_store_reviews_rating");
        assertThatThrownBy(() -> jdbc.update("UPDATE store_reviews SET content = ? WHERE id = ?", " \t", created.id()))
            .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("ck_store_reviews_content");
    }

    @Test
    void openApiPublishesReviewEndpointsAndStoreSummary() {
        var document = call("GET", "/v3/api-docs", null, null);
        assertThat(document.status()).isEqualTo(200);
        assertThat(document.body().get("paths").toString()).contains("/api/stores/{storeId}/reviews",
            "/api/stores/{storeId}/reviews/me", "/api/stores/{storeId}/reviews/summary", "/api/store-reviews/{reviewId}");
        assertThat(document.body().get("components").toString()).contains("reviewSummary");
    }

    private List<Result> race(Callable<Result> first, Callable<Result> second) throws Exception {
        var gate = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var a = executor.submit(() -> {
                gate.await(5, TimeUnit.SECONDS);
                return first.call();
            });
            var b = executor.submit(() -> {
                gate.await(5, TimeUnit.SECONDS);
                return second.call();
            });
            return List.of(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS));
        }
    }

    private User user() {
        String id = UUID.randomUUID().toString();
        return users.saveAndFlush(User.builder().nickname("리뷰 사용자 " + id.substring(0, 8))
            .email(id + "@example.test").authProvider(AuthProvider.KAKAO).providerUserId(id).build());
    }

    private Result create(User user, int rating) {
        var result = call("POST", reviewsPath(), user, input(rating));
        assertThat(result.status()).as("응답: %s", result.body()).isEqualTo(201);
        return result;
    }

    private String reviewsPath() {
        return "/api/stores/" + storeId + "/reviews";
    }

    private static String reviewPath(long id) {
        return "/api/store-reviews/" + id;
    }

    private static Map<String, Object> input(int rating) {
        return Map.of("rating", rating, "content", "매장 리뷰입니다.");
    }

    private Result call(String method, String path, User user, Object body) {
        var request = http.method(HttpMethod.valueOf(method)).uri(path);
        if (user != null) {
            request.headers(headers -> headers.setBearerAuth(jwt.createAccessToken(user.getId())));
        }
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).body(body);
        }
        return request.exchange((req, res) -> new Result(res.getStatusCode().value(), res.bodyTo(Map.class)));
    }

    private static void assertError(Result result, int status, String code) {
        assertThat(result.status()).as("응답: %s", result.body()).isEqualTo(status);
        assertThat(result.body().get("success")).isEqualTo(false);
        assertThat(((Map<?, ?>) result.body().get("error")).get("code")).isEqualTo(code);
    }

    private record Result(int status, Map<?, ?> body) {
        @SuppressWarnings("unchecked")
        Map<String, Object> data() {
            assertThat(body.get("data")).as("응답: %s", body).isInstanceOf(Map.class);
            return (Map<String, Object>) body.get("data");
        }

        long id() {
            return ((Number) data().get("reviewId")).longValue();
        }

        List<Long> ids() {
            return ((List<?>) data().get("content")).stream()
                .map(row -> ((Number) ((Map<?, ?>) row).get("reviewId")).longValue()).toList();
        }
    }
}
