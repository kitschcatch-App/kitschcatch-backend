// 실제 PostgreSQL과 HTTP에서 리뷰 사진 소유권, 연결, 정리와 동시성을 검증한다.
package com.kitschcatch.backend.domain.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.kitschcatch.backend.domain.store.entity.Store;
import com.kitschcatch.backend.domain.store.service.*;
import com.kitschcatch.backend.domain.store.repository.StoreReviewImageRepository;
import com.kitschcatch.backend.global.exception.*;
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

@EnabledIfEnvironmentVariable(named = "ISSUE87_TEST_DB_URL", matches = "jdbc:postgresql:.*")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.datasource.driver-class-name=org.postgresql.Driver", "spring.jpa.hibernate.ddl-auto=create",
    "kakao.oauth.native-app-key=test", "app.orders.expiration-enabled=false",
    "app.payments.recovery.enabled=false", "app.s3.bucket=review-test-bucket",
    "app.s3.download-url-ttl=12m", "springdoc.api-docs.enabled=true"
})
@DirtiesContext
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StoreReviewImagePostgresHttpTest {
    private static PostgresStoreTestDatabase database;

    @LocalServerPort int port;
    @Autowired StoreRepository stores;
    @Autowired UserRepository users;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtTokenProvider jwt;
    @TestBean(methodName = "testPresigner") S3Presigner presigner;

    @TestBean(methodName = "memoryStorage") StoreReviewImageStorage reviewImageStorage;
    @Autowired StoreReviewImageRepository imageRepository;
    @Autowired StoreReviewImageService imageService;
    @Autowired StoreReviewImageCleanup cleanup;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;
    private byte[] png;
    private RestClient http;
    private long storeId;
    private User author;
    private User reader;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) throws Exception {
        database = new PostgresStoreTestDatabase("87");
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
    void fixtures() throws Exception {
        var output = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(3, 2, java.awt.image.BufferedImage.TYPE_INT_RGB), "png", output);
        png = output.toByteArray();
        memory().objects.clear(); memory().publishes.set(0); memory().failDelete = false;
        var factory = new JdkClientHttpRequestFactory();
        factory.setReadTimeout(java.time.Duration.ofSeconds(15));
        http = RestClient.builder().baseUrl("http://127.0.0.1:" + port).requestFactory(factory).build();
        storeId = stores.saveAndFlush(Store.builder().name("리뷰 검증 매장").region("서울").address("검증 주소")
            .latitude(37.5).longitude(127).build()).getId();
        author = user();
        reader = user();
    }

    @Test
    void uploadCompleteAttachReadReorderRemoveAndDelete() {
        long first = ready(author), second = ready(author);
        var created = createWith(author, first, second);
        String path = reviewPath(created.id());
        assertThat(photoIds(created)).containsExactly(first, second);
        assertThat(photoIds(call("GET", path, reader, null))).containsExactly(first, second);
        var list = call("GET", reviewsPath(), reader, null);
        assertThat(list.data().get("content").toString()).contains("imageUrl", "sortOrder");
        assertThat(call("GET", galleryPath() + "?size=1", reader, null).data())
            .containsEntry("totalElements", 2).containsEntry("totalPages", 2);
        assertThat(galleryIds()).containsExactly(first, second);
        var unchanged = call("PATCH", path, author, Map.of("version", 0, "content", "본문만 변경"));
        assertThat(photoIds(unchanged)).containsExactly(first, second);
        var reordered = call("PATCH", path, author, Map.of("version", 1, "imageIds", List.of(second, first)));
        assertThat(reordered.status()).as("%s", reordered.body()).isEqualTo(200);
        assertThat(photoIds(reordered)).containsExactly(second, first);
        assertThat(galleryIds()).containsExactly(second, first);
        assertError(call("PATCH", path, author, Map.of("version", 1, "imageIds", List.of())), 409, "STORE_REVIEW_004");
        assertThat(photoIds(call("GET", path, reader, null))).containsExactly(second, first);
        var removed = call("PATCH", path, author, Map.of("version", 2, "imageIds", List.of(first)));
        assertThat(photoIds(removed)).containsExactly(first);
        assertThat(state(second)).isEqualTo("DELETE_PENDING");
        var empty = call("PATCH", path, author, Map.of("version", 3, "imageIds", List.of()));
        assertThat(photoIds(empty)).isEmpty();
        assertThat(state(first)).isEqualTo("DELETE_PENDING");
        long third = ready(author);
        var attached = call("PATCH", path, author, Map.of("version", 4, "imageIds", List.of(third)));
        assertThat(photoIds(attached)).containsExactly(third);
        assertThat(call("DELETE", path, author, null).status()).isEqualTo(200);
        assertThat(state(third)).isEqualTo("DELETE_PENDING");
        assertThat(galleryIds()).isEmpty();
        assertThat(call("GET", reviewsPath() + "/summary", reader, null).data()).containsEntry("reviewCount", 0);
    }

    @Test
    void completionIsIdempotentAndUploadsUseDistinctKeys() {
        long first = uploaded(author), second = uploaded(author);
        assertThat(temporaryKey(first)).isNotEqualTo(temporaryKey(second));
        assertError(call("POST", completePath(first), reader, null), 404, "STORE_REVIEW_IMAGE_002");
        var done = call("POST", completePath(first), author, null);
        assertThat(done.status()).isEqualTo(200);
        assertThat(done.data()).containsEntry("state", "READY").containsEntry("width", 3).containsEntry("height", 2);
        assertThat(call("POST", completePath(first), author, null).data()).isEqualTo(done.data());
        assertThat(memory().publishes.get()).isEqualTo(1);
        createWith(author, first);
        expire(first);
        assertThat(call("POST", completePath(first), author, null).data()).containsEntry("state", "ATTACHED");
        assertThat(memory().publishes.get()).isEqualTo(1);
    }

    @Test
    void rejectsMissingInvalidExpiredOrForeignUploadsAndRollsBackReview() {
        long pending = issue(author);
        assertError(call("POST", completePath(pending), author, null), 409, "STORE_REVIEW_IMAGE_003");
        memory().objects.put(temporaryKey(pending), new byte[] {1, 2, 3});
        assertError(call("POST", completePath(pending), author, null), 400, "STORE_REVIEW_IMAGE_001");
        assertThat(state(pending)).isEqualTo("PENDING");
        assertError(call("POST", reviewsPath(), author, reviewInput(List.of(pending))), 409, "STORE_REVIEW_IMAGE_004");
        assertThat(call("GET", reviewsPath() + "/me", author, null).data()).containsEntry("review", null);
        long foreign = ready(reader);
        assertError(call("POST", reviewsPath(), author, reviewInput(List.of(foreign))), 404, "STORE_REVIEW_IMAGE_002");
        long valid = ready(author);
        assertError(call("POST", reviewsPath(), author, reviewInput(List.of(valid, valid))), 400, "STORE_REVIEW_IMAGE_001");
        long savedStore = storeId;
        storeId = stores.saveAndFlush(Store.builder().name("다른 매장").region("서울").address("검증 주소")
            .latitude(37.5).longitude(127).build()).getId();
        assertError(call("POST", reviewsPath(), author, reviewInput(List.of(valid))), 404, "STORE_REVIEW_IMAGE_002");
        storeId = savedStore;
        expire(valid);
        assertError(call("POST", reviewsPath(), author, reviewInput(List.of(valid))), 409, "STORE_REVIEW_IMAGE_004");
        assertError(call("POST", completePath(valid), author, null), 409, "STORE_REVIEW_IMAGE_004");
        expire(pending);
        assertError(call("POST", completePath(pending), author, null), 409, "STORE_REVIEW_IMAGE_004");
    }

    @Test
    void boundsRequestsAndRequiresAuthentication() {
        assertThat(call("POST", uploadPath(), null, uploadInput()).status()).isEqualTo(401);
        assertThat(call("GET", galleryPath(), null, null).status()).isEqualTo(401);
        assertThat(call("POST", uploadPath(), author, Map.of("images", List.of())).status()).isEqualTo(400);
        var file = ((List<?>) uploadInput().get("images")).getFirst();
        assertThat(call("POST", uploadPath(), author, Map.of("images", java.util.Collections.nCopies(11, file))).status())
            .isEqualTo(400);
        for (var invalid : List.of(Map.of("fileName", "bad.gif", "contentType", "image/gif", "fileSize", 10),
                Map.of("fileName", "bad.jpg", "contentType", "image/png", "fileSize", 10),
                Map.of("fileName", "bad.png", "contentType", "image/png", "fileSize", 5242881))) {
            assertThat(call("POST", uploadPath(), author, Map.of("images", List.of(invalid))).status()).isEqualTo(400);
        }
        assertThat(call("GET", galleryPath() + "?size=101", author, null).status()).isEqualTo(400);
        assertThat(call("GET", "/api/stores/9223372036854775807/review-images", author, null).status()).isEqualTo(404);
        for (int n = 0; n < 10; n++) {
            assertThat(call("POST", uploadPath(), author, Map.of("images", java.util.Collections.nCopies(10, file))).status())
                .isEqualTo(200);
        }
        assertError(call("POST", uploadPath(), author, uploadInput()), 429, "STORE_REVIEW_IMAGE_005");
    }

    @Test
    void galleryExcludesHiddenDeletedAndWithdrawnReviewsAndPreservesHiddenEvidence() {
        long visible = ready(author);
        createWith(author, visible);
        var hiddenAuthor = user(); long hidden = ready(hiddenAuthor); var hiddenReview = createWith(hiddenAuthor, hidden);
        var withdrawn = user(); long withdrawnImage = ready(withdrawn); createWith(withdrawn, withdrawnImage);
        var deleted = user(); long deletedImage = ready(deleted); var deletedReview = createWith(deleted, deletedImage);
        jdbc.update("UPDATE store_reviews SET hidden_at = CURRENT_TIMESTAMP WHERE id = ?", hiddenReview.id());
        assertError(call("PATCH", reviewPath(hiddenReview.id()), hiddenAuthor, Map.of("version", 0, "imageIds", List.of())),
            409, "STORE_REVIEW_IMAGE_007");
        assertThat(call("DELETE", reviewPath(hiddenReview.id()), hiddenAuthor, null).status()).isEqualTo(200);
        assertThat(call("DELETE", reviewPath(deletedReview.id()), deleted, null).status()).isEqualTo(200);
        assertThat(call("DELETE", "/api/users/me", withdrawn, null).status()).isEqualTo(200);
        assertThat(galleryIds()).containsExactly(visible);
        for (long id : List.of(hidden, withdrawnImage, deletedImage, visible)) {
            expire(id); cleanup.clean(id);
        }
        assertThat(state(hidden)).isEqualTo("ATTACHED");
        assertThat(memory().objects).containsKey(objectKey(hidden));
        assertThat(state(visible)).isEqualTo("ATTACHED");
        assertThat(state(withdrawnImage)).isEqualTo("DELETED");
        assertThat(state(deletedImage)).isEqualTo("DELETED");
        assertThat(imageRepository.due(Instant.now(), Instant.now().minus(java.time.Duration.ofDays(90)), org.springframework.data.domain.PageRequest.of(0, 1000)))
            .doesNotContain(hidden, visible);
    }

    @Test
    void hiddenEvidenceOfWithdrawnAuthorExpires() {
        long image = ready(author);
        var review = createWith(author, image);
        jdbc.update("update store_reviews set hidden_at=now() where id=?", review.id());
        assertThat(call("DELETE", "/api/users/me", author, null).status()).isEqualTo(200);
        expire(image);
        cleanup.clean(image);
        assertThat(state(image)).isEqualTo("ATTACHED");

        jdbc.update("update users set withdrawn_at=now()-interval '91 days' where id=?", author.getId());
        var now = Instant.now();
        assertThat(imageRepository.due(now, now.minus(java.time.Duration.ofDays(90)),
            org.springframework.data.domain.PageRequest.of(0, 1000))).contains(image);
        cleanup.clean(image);
        assertThat(state(image)).isEqualTo("DELETED");
        assertThat(memory().objects).doesNotContainKey(objectKey(image));
    }

    @Test
    void expiredOrRemovedFilesAreCleanedAndFailuresAreRetried() {
        long pending = uploaded(author), ready = ready(author);
        cleanup.clean(pending);
        assertThat(memory().objects).containsKey(temporaryKey(pending));
        expire(pending); expire(ready);
        memory().failDelete = true;
        cleanup.clean(ready);
        assertThat(state(ready)).isEqualTo("READY");
        assertThat(jdbc.queryForObject("SELECT cleanup_attempts FROM store_review_images WHERE id=?", Integer.class, ready))
            .isEqualTo(1);
        assertThat(imageRepository.due(Instant.now(), Instant.now().minus(java.time.Duration.ofDays(90)), org.springframework.data.domain.PageRequest.of(0, 1000)))
            .doesNotContain(ready);
        memory().failDelete = false;
        expire(ready);
        cleanup.clean(ready); cleanup.clean(pending); cleanup.clean(ready);
        assertThat(state(ready)).isEqualTo("DELETED");
        assertThat(state(pending)).isEqualTo("DELETED");
        assertThat(memory().objects).doesNotContainKeys(temporaryKey(ready), objectKey(ready), temporaryKey(pending));
    }

    @Test
    void finalObjectSurvivesTransactionRollbackAndRetryUsesSameKey() {
        long image = uploaded(author);
        var tx = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        tx.executeWithoutResult(status -> {
            imageService.complete(author.getId(), image);
            status.setRollbackOnly();
        });
        assertThat(state(image)).isEqualTo("PENDING");
        assertThat(memory().objects).containsKey(objectKey(image));
        assertThat(call("POST", completePath(image), author, null).status()).isEqualTo(200);
        assertThat(state(image)).isEqualTo("READY");
        long orphan = uploaded(author);
        tx.executeWithoutResult(status -> { imageService.complete(author.getId(), orphan); status.setRollbackOnly(); });
        expire(orphan); cleanup.clean(orphan);
        assertThat(memory().objects).doesNotContainKey(objectKey(orphan));
    }

    @Test
    void concurrentCompletionsAndEditsHaveConsistentResults() throws Exception {
        long photo = uploaded(author);
        var completions = race(() -> call("POST", completePath(photo), author, null),
            () -> call("POST", completePath(photo), author, null));
        assertThat(completions).extracting(Result::status).containsExactly(200, 200);
        assertThat(memory().publishes.get()).isEqualTo(1);
        long other = ready(author);
        var review = createWith(author, photo, other);
        var results = race(() -> call("PATCH", reviewPath(review.id()), author, Map.of("version", 0, "imageIds", List.of(other, photo))),
            () -> call("PATCH", reviewPath(review.id()), author, Map.of("version", 0, "imageIds", List.of())));
        assertThat(results).extracting(Result::status).containsExactlyInAnyOrder(200, 409);
        var winner = results.stream().filter(result -> result.status() == 200).findFirst().orElseThrow();
        assertThat(galleryIds()).isEqualTo(photoIds(winner));
    }

    @Test
    void cleanupAndExpiredCompletionRaceCannotResurrectFiles() throws Exception {
        long image = uploaded(author);
        expire(image);
        var results = race(() -> call("POST", completePath(image), author, null), () -> {
            cleanup.clean(image);
            return new Result(200, Map.of());
        });
        assertThat(results).extracting(Result::status).containsExactly(409, 200);
        assertThat(state(image)).isEqualTo("DELETED");
        assertThat(memory().objects).doesNotContainKeys(temporaryKey(image), objectKey(image));
    }

    @Test
    void concurrentWithdrawalAndAttachmentLeaveNoPublicPhotos() throws Exception {
        long image = ready(author);
        var results = race(() -> call("POST", reviewsPath(), author, reviewInput(List.of(image))),
            () -> call("DELETE", "/api/users/me", author, null));
        assertThat(results.get(0).status()).isIn(201, 401);
        assertThat(results.get(1).status()).isEqualTo(200);
        assertThat(galleryIds()).isEmpty();
        expire(image); cleanup.clean(image);
        assertThat(state(image)).isEqualTo("DELETED");
    }

    @Test
    void sqlProtectsOwnershipOrderAndStateAndCanBeReapplied() throws Exception {
        long photo = ready(author), foreign = ready(reader);
        var review = createWith(author, photo);
        try (var connection = database.connect()) { PostgresStoreTestDatabase.migrateReviewImages(connection); }
        assertThat(galleryIds()).containsExactly(photo);
        assertThatThrownBy(() -> jdbc.update("UPDATE store_review_images SET review_id=?,sort_order=1,state='ATTACHED' WHERE id=?",
            review.id(), foreign)).isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("fk_review_images_owner");
        long duplicateOrder = ready(author);
        assertThatThrownBy(() -> jdbc.update("UPDATE store_review_images SET review_id=?,sort_order=0,state='ATTACHED' WHERE id=?",
            review.id(), duplicateOrder)).isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("uk_review_images_order");
        assertThatThrownBy(() -> jdbc.update("UPDATE store_review_images SET sort_order=10 WHERE id=?", photo))
            .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("ck_review_images_attachment");
        assertThatThrownBy(() -> jdbc.update("UPDATE store_review_images SET width=NULL WHERE id=?", photo))
            .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("ck_review_images_dimensions");
        assertThatThrownBy(() -> jdbc.update("UPDATE store_review_images SET state='READY' WHERE id=?", photo))
            .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("ck_review_images_attachment");
    }

    @Test
    void openApiIncludesNewRoutesAndPhotoIds() {
        var document = call("GET", "/v3/api-docs", null, null);
        assertThat(document.status()).isEqualTo(200);
        assertThat(document.body().get("paths").toString()).contains("/api/stores/{storeId}/review-images/upload-urls",
            "/api/store-review-images/{imageId}/complete", "/api/stores/{storeId}/review-images");
        assertThat(document.body().get("components").toString()).contains("imageIds");
    }

    private long issue(User user) {
        var result = call("POST", uploadPath(), user, uploadInput());
        assertThat(result.status()).as("%s", result.body()).isEqualTo(200);
        return ((Number) ((Map<?, ?>) ((List<?>) result.data().get("images")).getFirst()).get("imageId")).longValue();
    }
    private long uploaded(User user) {
        long id = issue(user);
        memory().objects.put(temporaryKey(id), png);
        return id;
    }
    private long ready(User user) {
        long id = uploaded(user);
        var result = call("POST", completePath(id), user, null);
        assertThat(result.status()).as("%s", result.body()).isEqualTo(200);
        return id;
    }
    private Result createWith(User user, Long... ids) {
        var result = call("POST", reviewsPath(), user, reviewInput(List.of(ids)));
        assertThat(result.status()).as("%s", result.body()).isEqualTo(201);
        return result;
    }
    private Map<String, Object> uploadInput() {
        return Map.of("images", List.of(Map.of("fileName", "photo.png", "contentType", "image/png", "fileSize", png.length)));
    }
    private Map<String, Object> reviewInput(List<Long> ids) {
        return Map.of("rating", 5, "content", "사진을 첨부한 리뷰", "imageIds", ids);
    }
    private List<Long> photoIds(Result result) {
        return ((List<?>) result.data().get("images")).stream().map(row -> ((Number) ((Map<?, ?>) row).get("id")).longValue()).toList();
    }
    private List<Long> galleryIds() {
        return ((List<?>) call("GET", galleryPath(), reader, null).data().get("content")).stream()
            .map(row -> ((Number) ((Map<?, ?>) row).get("id")).longValue()).toList();
    }
    private String uploadPath() { return galleryPath() + "/upload-urls"; }
    private String galleryPath() { return "/api/stores/" + storeId + "/review-images"; }
    private String completePath(long id) { return "/api/store-review-images/" + id + "/complete"; }
    private String temporaryKey(long id) { return jdbc.queryForObject("SELECT temporary_key FROM store_review_images WHERE id=?", String.class, id); }
    private String objectKey(long id) { return jdbc.queryForObject("SELECT object_key FROM store_review_images WHERE id=?", String.class, id); }
    private String state(long id) { return jdbc.queryForObject("SELECT state FROM store_review_images WHERE id=?", String.class, id); }
    private void expire(long id) {
        jdbc.update("UPDATE store_review_images SET created_at=CURRENT_TIMESTAMP-INTERVAL '25 hours', expires_at=CURRENT_TIMESTAMP-INTERVAL '1 hour', cleanup_at=CURRENT_TIMESTAMP-INTERVAL '1 hour' WHERE id=?", id);
    }
    private MemoryStorage memory() { return (MemoryStorage) reviewImageStorage; }
    static StoreReviewImageStorage memoryStorage() { return new MemoryStorage(); }
    static class MemoryStorage implements StoreReviewImageStorage {
        final java.util.concurrent.ConcurrentHashMap<String, byte[]> objects = new java.util.concurrent.ConcurrentHashMap<>();
        final java.util.concurrent.atomic.AtomicInteger publishes = new java.util.concurrent.atomic.AtomicInteger();
        volatile boolean failDelete;
        public Upload upload(String key, String type, long size) { return new Upload("https://example.test/" + key, Instant.now().plusSeconds(300), Map.of("Content-Type", type)); }
        public byte[] read(String key, String type, long size) {
            var bytes = objects.get(key);
            if (bytes == null) throw new BusinessException(ErrorCode.STORE_REVIEW_IMAGE_NOT_UPLOADED);
            return bytes;
        }
        public void publish(String key, byte[] bytes) {
            publishes.incrementAndGet();
            var existing = objects.putIfAbsent(key, bytes);
            if (existing != null) assertThat(existing).isEqualTo(bytes);
        }
        public String imageUrl(String key) { return "https://example.test/" + key + "?signed=true"; }
        public void delete(String key) { if (failDelete) throw new IllegalStateException("simulated storage failure"); objects.remove(key); }
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

    private String reviewsPath() {
        return "/api/stores/" + storeId + "/reviews";
    }

    private static String reviewPath(long id) {
        return "/api/store-reviews/" + id;
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


    }
}
