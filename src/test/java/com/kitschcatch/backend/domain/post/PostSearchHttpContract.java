// 상품 검색과 최근 본 상품 추천의 인증·정렬·응답 계약을 실제 HTTP로 검증한다.
package com.kitschcatch.backend.domain.post;

import static com.kitschcatch.backend.support.S3GetUrlAssertions.assertGetUrl;
import org.springframework.test.context.bean.override.convention.TestBean;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;

import com.kitschcatch.backend.domain.order.toss.TossPaymentsClient;
import com.kitschcatch.backend.domain.post.entity.*;
import com.kitschcatch.backend.domain.post.repository.PostRepository;
import com.kitschcatch.backend.domain.user.entity.AuthProvider;
import com.kitschcatch.backend.domain.user.entity.User;
import com.kitschcatch.backend.domain.user.repository.UserRepository;
import com.kitschcatch.backend.global.security.JwtTokenProvider;
import jakarta.persistence.EntityManagerFactory;
import java.net.URLEncoder;
import javax.sql.DataSource;
import java.util.concurrent.atomic.AtomicReference;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.springframework.boot.hibernate.autoconfigure.HibernatePropertiesCustomizer;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
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

@Import(PostSearchHttpContract.SqlObservation.class)
abstract class PostSearchHttpContract {
    @TestBean(methodName = "testPresigner") S3Presigner s3Presigner;

    static S3Presigner testPresigner() {
        return S3Presigner.builder().region(Region.AP_NORTHEAST_2)
            .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test-access", "test-secret")))
            .build();
    }

    @TestConfiguration
    static class SqlObservation {
        @Bean CountBoundary countBoundary() { return new CountBoundary(); }
        @Bean HibernatePropertiesCustomizer searchSqlObserver(CountBoundary boundary) {
            return properties -> properties.put("hibernate.session_factory.statement_inspector", boundary);
        }
    }

    static class CountBoundary implements StatementInspector {
        final AtomicReference<Runnable> beforeCount = new AtomicReference<>();
        @Override public String inspect(String sql) {
            if (sql.startsWith("select count(") && (sql.contains(" from posts ") || sql.contains(".posts "))) {
                var callback = beforeCount.getAndSet(null);
                if (callback != null) callback.run();
            }
            return sql;
        }
    }

    @Autowired CountBoundary countBoundary;
    @Autowired DataSource dataSource;
    @LocalServerPort int port;
    @Autowired PostRepository posts;
    @Autowired UserRepository users;
    @Autowired JwtTokenProvider tokens;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManagerFactory entityManagerFactory;
    @MockitoBean S3Client s3;
    @MockitoBean TossPaymentsClient toss;
    User seller;
    String token;

    @BeforeEach
    void fixture() {
        jdbc.update("DELETE FROM user_blocks");
        jdbc.update("DELETE FROM post_images");
        jdbc.update("DELETE FROM posts");
        jdbc.update("DELETE FROM users");
        seller = users.save(User.builder().nickname("검색 판매자").email("search@example.test")
            .authProvider(AuthProvider.KAKAO).providerUserId(UUID.randomUUID().toString()).build());
        token = tokens.createAccessToken(seller.getId());
    }

    @Test
    void requiresAccessJwtForExistingUser() {
        for (String invalid : new String[]{null, "invalid", tokens.createRefreshToken(seller.getId()), tokens.createAccessToken(Long.MAX_VALUE)}) {
            error(request(HttpMethod.GET, "/api/posts", invalid), 401, "AUTH_004");
        }
        assertThat(search(Map.of()).status()).isEqualTo(200);
    }

    @Test
    void combinesEveryFilterAndIncludesBothPriceBoundaries() {
        long low = post("키링", "Anime 굿즈", 100, ProductCategory.GOODS, ProductCondition.NEW, ProductStatus.ON_SALE);
        long high = post("Anime 키링", "미개봉", 200, ProductCategory.GOODS, ProductCondition.NEW, ProductStatus.ON_SALE);
        post("Anime 키링", "미개봉", 99, ProductCategory.GOODS, ProductCondition.NEW, ProductStatus.ON_SALE);
        post("Anime 키링", "미개봉", 201, ProductCategory.GOODS, ProductCondition.NEW, ProductStatus.ON_SALE);
        post("Anime 키링", "미개봉", 150, ProductCategory.GAME, ProductCondition.NEW, ProductStatus.ON_SALE);
        post("Anime 키링", "미개봉", 150, ProductCategory.GOODS, ProductCondition.USED, ProductStatus.ON_SALE);
        post("Anime 키링", "미개봉", 150, ProductCategory.GOODS, ProductCondition.NEW, ProductStatus.RESERVED);
        post("다른 상품", "설명", 150, ProductCategory.GOODS, ProductCondition.NEW, ProductStatus.ON_SALE);
        long deleted = post("Anime 키링", "미개봉", 150, ProductCategory.GOODS, ProductCondition.NEW, ProductStatus.ON_SALE);
        assertThat(request(HttpMethod.DELETE, "/api/posts/" + deleted, token).status()).isEqualTo(200);
        var response = search(Map.of("keyword", " anime ", "category", "굿즈", "condition", "NEW", "status", "ON_SALE",
            "minPrice", "100", "maxPrice", "200", "sort", "PRICE_ASC"));
        assertThat(response.ids()).containsExactly(low, high);
        assertThat(response.data()).containsEntry("totalElements", 2);
        assertThat(search(Map.of("minPrice", "200", "maxPrice", "200", "category", "GOODS", "status", "ON_SALE")).ids()).containsExactly(high);
    }

    @ParameterizedTest
    @EnumSource(ProductStatus.class)
    void filtersEverySaleStatus(ProductStatus status) {
        long selected = 0;
        for (var candidate : ProductStatus.values()) {
            long id = post(candidate.name(), "설명", 100, ProductCategory.GOODS, ProductCondition.NEW, candidate);
            if (candidate == status) selected = id;
        }
        assertThat(search(Map.of("status", status.name())).ids()).containsExactly(selected);
        assertThat(search(Map.of()).ids()).hasSize(3);
    }

    @ParameterizedTest
    @EnumSource(ProductCategory.class)
    void acceptsCategoryNameAndExistingLabel(ProductCategory category) {
        long selected = post("카테고리", "설명", 100, category, ProductCondition.NEW, ProductStatus.ON_SALE);
        for (String value : List.of(category.name(), category.getLabel())) {
            assertThat(search(Map.of("category", value)).ids()).containsExactly(selected);
        }
    }

    @ParameterizedTest
    @EnumSource(ProductCondition.class)
    void filtersEveryCondition(ProductCondition condition) {
        long selected = 0;
        for (var candidate : ProductCondition.values()) {
            long id = post(candidate.name(), "설명", 100, ProductCategory.GOODS, candidate, ProductStatus.ON_SALE);
            if (candidate == condition) selected = id;
        }
        assertThat(search(Map.of("condition", condition.name())).ids()).containsExactly(selected);
    }

    @Test
    void matchesTitleOrDescriptionAndEscapesSqlWildcards() {
        long literal = post("100%_! 한정", "Anime 이야기", 0, ProductCategory.GOODS, ProductCondition.NEW, ProductStatus.ON_SALE);
        post("100AB 한정", "설명", 100, ProductCategory.GOODS, ProductCondition.NEW, ProductStatus.ON_SALE);
        for (String keyword : List.of("%", "_", "!", "%_!", "한정", "ANIME", "' OR 1=1 --")) {
            var result = search(Map.of("keyword", keyword));
            if (keyword.equals("' OR 1=1 --")) assertThat(result.rows()).isEmpty();
            else if (keyword.equals("한정")) assertThat(result.rows()).hasSize(2);
            else assertThat(result.ids()).containsExactly(literal);
        }
        assertThat(search(Map.of("minPrice", "0", "maxPrice", "0")).ids()).containsExactly(literal);
        assertThat(search(Map.of("keyword", "   ")).rows()).hasSize(2);
        assertThat(search(Map.of("minPrice", "0", "maxPrice", Long.toString(Long.MAX_VALUE))).rows()).hasSize(2);
    }

    @Test
    void tiesHaveStableIdOrderAndPagesDoNotOverlap() {
        long low = post("저가", "설명", 1, ProductCategory.GOODS, ProductCondition.NEW, ProductStatus.ON_SALE);
        long first = post("동률1", "설명", 100, ProductCategory.GOODS, ProductCondition.NEW, ProductStatus.ON_SALE);
        long second = post("동률2", "설명", 100, ProductCategory.GOODS, ProductCondition.NEW, ProductStatus.ON_SALE);
        long high = post("고가", "설명", 200, ProductCategory.GOODS, ProductCondition.NEW, ProductStatus.ON_SALE);
        jdbc.update("UPDATE posts SET created_at=TIMESTAMP '2026-09-01 12:00:00'");
        assertThat(search(Map.of()).ids()).containsExactly(high, second, first, low);
        assertThat(search(Map.of("sort", "LATEST")).ids()).containsExactly(high, second, first, low);
        assertThat(search(Map.of("sort", "PRICE_ASC")).ids()).containsExactly(low, second, first, high);
        assertThat(search(Map.of("sort", "PRICE_DESC")).ids()).containsExactly(high, second, first, low);
        assertThat(search(Map.of("sort", "PRICE_ASC", "page", "0", "size", "2")).ids()).containsExactly(low, second);
        var next = search(Map.of("sort", "PRICE_ASC", "page", "1", "size", "2"));
        assertThat(next.ids()).containsExactly(first, high);
        assertThat(next.data()).containsEntry("totalElements", 4).containsEntry("totalPages", 2).containsEntry("number", 1).containsEntry("size", 2);
        jdbc.update("UPDATE posts SET created_at=TIMESTAMP '2026-09-02 12:00:00' WHERE id=?", first);
        assertThat(search(Map.of("sort", "PRICE_ASC")).ids()).containsExactly(low, first, second, high);
    }

    @Test
    void emptyAndOutOfRangeResultsRemainSuccessfulAndKeepTotals() {
        var empty = search(Map.of());
        assertThat(empty.rows()).isEmpty();
        assertThat(empty.data()).containsEntry("totalElements", 0).containsEntry("totalPages", 0);
        post("상품", "설명", 100, ProductCategory.GOODS, ProductCondition.NEW, ProductStatus.ON_SALE);
        assertThat(search(Map.of("keyword", "없는 검색어")).rows()).isEmpty();
        var outside = search(Map.of("page", "10000", "size", "100"));
        assertThat(outside.rows()).isEmpty();
        assertThat(outside.data()).containsEntry("totalElements", 1).containsEntry("number", 10000).containsEntry("size", 100);
    }

    @Test
    void pageLimitIsAppliedInSqlAndDefaultsStayCompatible() {
        for (int i = 0; i < 101; i++) {
            post("상품" + i, "설명", i, ProductCategory.GOODS, ProductCondition.NEW, ProductStatus.ON_SALE);
        }
        var defaults = search(Map.of());
        assertThat(defaults.rows()).hasSize(20);
        assertThat(defaults.data()).containsEntry("size", 20).containsEntry("number", 0).containsEntry("totalElements", 101);
        var maximum = search(Map.of("size", "100"));
        assertThat(maximum.rows()).hasSize(100);
        assertThat(search(Map.of("page", "1", "size", "100")).rows()).hasSize(1);
    }

    @Test
    void concurrentDeletionBetweenContentAndCountUsesOneSnapshot() {
        post("상품1", "설명", 100, ProductCategory.GOODS, ProductCondition.NEW, ProductStatus.ON_SALE);
        long removed = post("상품2", "설명", 100, ProductCategory.GOODS, ProductCondition.NEW, ProductStatus.ON_SALE);
        long newest = post("상품3", "설명", 100, ProductCategory.GOODS, ProductCondition.NEW, ProductStatus.ON_SALE);
        countBoundary.beforeCount.set(() -> {
            // 검색 트랜잭션과 다른 연결에서 삭제를 커밋해 content/count 사이의 변경을 재현한다.
            try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement("UPDATE posts SET deleted_at=CURRENT_TIMESTAMP WHERE id=?")) {
                connection.setAutoCommit(true);
                statement.setLong(1, removed);
                assertThat(statement.executeUpdate()).isEqualTo(1);
            } catch (java.sql.SQLException exception) {
                throw new IllegalStateException(exception);
            }
        });
        try {
            var first = search(Map.of("size", "1"));
            assertThat(first.ids()).containsExactly(newest);
            assertThat(first.data()).containsEntry("totalElements", 3);
            assertThat(countBoundary.beforeCount.get()).isNull();
            assertThat(search(Map.of("size", "1")).data()).containsEntry("totalElements", 2);
        } finally {
            countBoundary.beforeCount.set(null);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"page=-1", "page=10001", "page=2147483647", "page=2147483648", "page=abc", "size=0", "size=101", "size=abc",
        "status=", "status=on_sale", "status=UNKNOWN", "category=", "category=unknown", "condition=", "condition=new", "condition=UNKNOWN",
        "sort=", "sort=price", "sort=createdAt,desc", "minPrice=-1", "maxPrice=-1", "minPrice=1.5", "maxPrice=abc", "maxPrice=9223372036854775808", "minPrice=200&maxPrice=100"})
    void rejectsInvalidSearchInputs(String query) {
        error(request(HttpMethod.GET, "/api/posts?" + query, token), 400, "COMMON_001");
    }

    @Test
    void rejectsOverlongKeywordAndPreservesDetailInputError() {
        error(search(Map.of("keyword", "a".repeat(1001))), 400, "COMMON_001");
        error(request(HttpMethod.GET, "/api/posts/not-an-id", token), 400, "COMMON_002");
    }

    @Test
    void returnsExistingResponseImagesWithoutExternalCallsOrNPlusOne() {
        for (int i = 0; i < 25; i++) {
            post("상품" + i, "설명", i, ProductCategory.GOODS, ProductCondition.NEW, ProductStatus.ON_SALE);
        }
        var statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();
        var response = search(Map.of("size", "25"));
        assertThat(response.rows()).hasSize(25);
        var row = response.rows().getFirst();
        assertThat(row).containsEntry("sellerNickname", "검색 판매자").containsEntry("productCategory", "굿즈")
            .containsEntry("productCondition", "NEW").containsEntry("productStatus", "ON_SALE");
        assertThat((List<?>) row.get("images")).hasSize(2);
        @SuppressWarnings("unchecked") var image = ((List<Map<String, Object>>) row.get("images")).getFirst();
        assertThat(image).containsEntry("sortOrder", 0);
        assertGetUrl(image.get("imageUrl").toString(), "test-bucket", image.get("imageKey").toString(),
            900, "test-access", "test-secret");
        assertThat(statistics.getPrepareStatementCount()).isLessThanOrEqualTo(8); // 집계 2회 및 인증 시 종료 계정 확인 쿼리 1회 포함
        assertThat(statistics.getEntityInsertCount() + statistics.getEntityUpdateCount() + statistics.getEntityDeleteCount()).isZero();
        verifyNoInteractions(s3, toss);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM posts WHERE deleted_at IS NOT NULL OR product_status <> 'ON_SALE'", Long.class)).isZero();
    }

    @Test
    void exposesSearchContractInSwagger() {
        var result = request(HttpMethod.GET, "/v3/api-docs", null);
        assertThat(result.status()).isEqualTo(200);
        @SuppressWarnings("unchecked") var paths = (Map<String, Object>) result.body().get("paths");
        @SuppressWarnings("unchecked") var path = (Map<String, Object>) paths.get("/api/posts");
        @SuppressWarnings("unchecked") var operation = (Map<String, Object>) path.get("get");
        @SuppressWarnings("unchecked") var parameters = (List<Map<String, Object>>) operation.get("parameters");
        assertThat(parameters.stream().map(p -> p.get("name"))).containsExactlyInAnyOrder("status", "keyword", "category", "condition", "minPrice", "maxPrice", "sort", "page", "size");
        assertThat(paths.get("/api/posts/recommendations/recently-viewed").toString())
            .contains("post=", "bearerAuth", "requestBody", "PostRecommendationRequest", "PostRecommendationResponse");
    }

    @Test
    void recommendationsRankByViewingOrderThenPriceThenLatestAndId() {
        var buyer = recommendationViewer();
        long oldGoods = recommendedPost(seller, "예전에 본 굿즈", 10_000, ProductCategory.GOODS, ProductStatus.ON_SALE);
        long game = recommendedPost(seller, "두 번째 게임", 50_000, ProductCategory.GAME, ProductStatus.RESERVED);
        long goods = recommendedPost(seller, "최근에 본 품절 굿즈", 30_000, ProductCategory.GOODS, ProductStatus.SOLD_OUT);
        long farther = recommendedPost(seller, "가격 차이가 큰 상품", 10_000, ProductCategory.GOODS, ProductStatus.ON_SALE);
        long earlier = recommendedPost(seller, "가격 동률 중 오래된 상품", 29_000, ProductCategory.GOODS, ProductStatus.ON_SALE);
        long firstTie = recommendedPost(seller, "가격과 시각 동률 1", 31_000, ProductCategory.GOODS, ProductStatus.ON_SALE);
        long secondTie = recommendedPost(seller, "가격과 시각 동률 2", 29_000, ProductCategory.GOODS, ProductStatus.ON_SALE);
        long gameCandidate = recommendedPost(seller, "가격이 같은 게임", 50_000, ProductCategory.GAME, ProductStatus.ON_SALE);
        recommendedPost(seller, "다른 카테고리", 30_000, ProductCategory.BOOK, ProductStatus.ON_SALE);
        jdbc.update("UPDATE posts SET created_at = TIMESTAMP '2026-01-02 00:00:00'");
        jdbc.update("UPDATE posts SET created_at = TIMESTAMP '2026-01-01 00:00:00' WHERE id = ?", earlier);

        String ids = "[%d,%d,%d,%d]".formatted(goods, game, goods, oldGoods);
        var response = recommend("{\"recentPostIds\":" + ids + "}", buyer);
        assertThat(response.status()).isEqualTo(200);
        assertThat(response.data()).containsOnlyKeys("limit", "posts").containsEntry("limit", 10);
        assertThat(recommendedIds(response)).containsExactly(secondTie, firstTie, earlier, farther, gameCandidate);
        assertThat(recommendedIds(recommend("{\"recentPostIds\":" + ids + ",\"limit\":2}", buyer)))
            .containsExactly(secondTie, firstTie);
        assertThat(recommendedIds(recommend("{\"recentPostIds\":[%d,%d],\"limit\":1}".formatted(game, goods), buyer)))
            .containsExactly(gameCandidate);
    }

    @Test
    void recommendationsFilterIneligibleSeedsAndCandidatesBeforeLimit() {
        var viewer = newUser();
        var blocked = newUser();
        var blocker = newUser();
        var withdrawn = newUser();
        long deleted = recommendedPost(seller, "삭제 상품", 30, ProductCategory.GOODS, ProductStatus.ON_SALE);
        jdbc.update("UPDATE posts SET deleted_at = CURRENT_TIMESTAMP WHERE id = ?", deleted);
        long own = recommendedPost(viewer, "내 상품", 30, ProductCategory.GOODS, ProductStatus.ON_SALE);
        long blockedPost = recommendedPost(blocked, "내가 차단한 판매자 상품", 30, ProductCategory.GOODS, ProductStatus.ON_SALE);
        long blockerPost = recommendedPost(blocker, "나를 차단한 판매자 상품", 30, ProductCategory.GOODS, ProductStatus.ON_SALE);
        long withdrawnPost = recommendedPost(withdrawn, "탈퇴 판매자 상품", 30, ProductCategory.GOODS, ProductStatus.ON_SALE);
        jdbc.update("UPDATE users SET withdrawn_at = CURRENT_TIMESTAMP WHERE id = ?", withdrawn.getId());
        jdbc.update("INSERT INTO user_blocks(blocker_id, blocked_id, created_at) VALUES (?, ?, CURRENT_TIMESTAMP)",
            viewer.getId(), blocked.getId());
        jdbc.update("INSERT INTO user_blocks(blocker_id, blocked_id, created_at) VALUES (?, ?, CURRENT_TIMESTAMP)",
            blocker.getId(), viewer.getId());
        recommendedPost(seller, "예약 상품", 30, ProductCategory.GOODS, ProductStatus.RESERVED);
        recommendedPost(seller, "판매 완료 상품", 30, ProductCategory.GOODS, ProductStatus.SOLD_OUT);
        long source = recommendedPost(seller, "정상 기준", 30, ProductCategory.GOODS, ProductStatus.ON_SALE);
        long candidate = recommendedPost(seller, "추천 후보", 90, ProductCategory.GOODS, ProductStatus.ON_SALE);
        String jwt = tokens.createAccessToken(viewer.getId());

        var result = recommend("{\"recentPostIds\":[%d],\"limit\":1}".formatted(source), jwt);
        assertThat(recommendedIds(result)).containsExactly(candidate);

        String ignoredIds = "%d,%d,%d,%d,%d,%d".formatted(deleted, own, blockedPost, blockerPost, withdrawnPost, Long.MAX_VALUE);
        assertThat(recommendedIds(recommend("{\"recentPostIds\":[" + ignoredIds + "]}", jwt))).isEmpty();
        assertThat(recommendedIds(recommend("{\"recentPostIds\":[" + ignoredIds + "," + source + "]}", jwt)))
            .containsExactly(candidate);
    }

    @Test
    void recommendationsReturnEmptySuccessWithoutHistoryOrCandidates() {
        String jwt = recommendationViewer();
        var empty = recommend("{\"recentPostIds\":[]}", jwt);
        assertThat(empty.status()).isEqualTo(200);
        assertThat(empty.data()).containsEntry("limit", 10);
        assertThat(recommendedIds(empty)).isEmpty();
        long source = recommendedPost(seller, "유일한 상품", 10, ProductCategory.GOODS, ProductStatus.ON_SALE);
        assertThat(recommendedIds(recommend("{\"recentPostIds\":[%d]}".formatted(source), jwt))).isEmpty();
    }

    @Test
    void recommendationsRequireAnActiveAccessToken() {
        var withdrawn = newUser();
        jdbc.update("UPDATE users SET withdrawn_at = CURRENT_TIMESTAMP WHERE id = ?", withdrawn.getId());
        for (String invalid : new String[]{null, "invalid", tokens.createRefreshToken(seller.getId()),
            tokens.createAccessToken(Long.MAX_VALUE), tokens.createAccessToken(withdrawn.getId())}) {
            error(recommend("{\"recentPostIds\":[]}", invalid), 401, "AUTH_004");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"recentPostIds\":null}", "{\"recentPostIds\":[null]}",
        "{\"recentPostIds\":[0]}", "{\"recentPostIds\":[-1]}", "{\"recentPostIds\":[],\"limit\":0}",
        "{\"recentPostIds\":[],\"limit\":21}",
        "{\"recentPostIds\":[1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1]}"})
    void recommendationsRejectInvalidInputValues(String body) {
        error(recommend(body, token), 400, "COMMON_001");
    }

    @ParameterizedTest
    @ValueSource(strings = {"{", "[]", "{\"recentPostIds\":1}", "{\"recentPostIds\":[\"1\"]}",
        "{\"recentPostIds\":[1.5]}", "{\"recentPostIds\":[9223372036854775808]}",
        "{\"recentPostIds\":[],\"limit\":null}", "{\"recentPostIds\":[],\"limit\":\"10\"}",
        "{\"recentPostIds\":[],\"limit\":1.5}"})
    void recommendationsRejectMalformedBodies(String body) {
        error(recommend(body, token), 400, "COMMON_002");
    }

    @Test
    void recommendationsBatchExistingProductResponseWithoutWritesOrExternalCalls() {
        String jwt = recommendationViewer();
        long source = recommendedPost(seller, "추천 기준", 0, ProductCategory.GOODS, ProductStatus.ON_SALE);
        for (int i = 0; i < 21; i++) {
            post("추천 상품 " + i, "설명", Long.MAX_VALUE - i, ProductCategory.GOODS, ProductCondition.NEW, ProductStatus.ON_SALE);
        }
        var statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();

        String recentIds = java.util.stream.LongStream.range(1, 20).mapToObj(i -> Long.toString(Long.MAX_VALUE - i))
            .collect(Collectors.joining(",", "[" + source + ",", "]"));
        var response = recommend("{\"recentPostIds\":" + recentIds + ",\"limit\":20}", jwt);
        assertThat(response.status()).isEqualTo(200);
        assertThat(recommendedIds(response)).hasSize(20);
        @SuppressWarnings("unchecked") var rows = (List<Map<String, Object>>) response.data().get("posts");
        var row = rows.getFirst();
        assertThat(row).containsEntry("productCategory", "굿즈").containsEntry("productStatus", "ON_SALE")
            .containsEntry("favoriteCount", 0).containsEntry("chatRoomCount", 0).containsEntry("shippingAmount", null);
        assertThat(row.get("price")).isEqualTo(Long.MAX_VALUE - 20);
        @SuppressWarnings("unchecked") var images = (List<Map<String, Object>>) row.get("images");
        assertThat(images).extracting(image -> image.get("sortOrder")).containsExactly(0, 1);
        var image = images.getFirst();
        assertGetUrl(image.get("imageUrl").toString(), "test-bucket", image.get("imageKey").toString(),
            900, "test-access", "test-secret");
        assertThat(statistics.getPrepareStatementCount()).isLessThanOrEqualTo(8);
        assertThat(statistics.getEntityInsertCount() + statistics.getEntityUpdateCount() + statistics.getEntityDeleteCount()).isZero();
        verifyNoInteractions(s3, toss);
    }

    private User newUser() {
        String id = UUID.randomUUID().toString();
        return users.save(User.builder().nickname("추천 검증 사용자").email(id + "@example.test")
            .authProvider(AuthProvider.KAKAO).providerUserId(id).build());
    }

    private String recommendationViewer() {
        return tokens.createAccessToken(newUser().getId());
    }

    private long recommendedPost(User author, String title, long price, ProductCategory category, ProductStatus status) {
        return posts.save(Post.builder().user(author).title(title).description("설명").price(price)
            .productCategory(category).productCondition(ProductCondition.NEW).productStatus(status).build()).getId();
    }

    private Response recommend(String body, String accessToken) {
        var request = RestClient.create().post().uri("http://127.0.0.1:" + port + "/api/posts/recommendations/recently-viewed")
            .contentType(org.springframework.http.MediaType.APPLICATION_JSON).body(body);
        if (accessToken != null) request.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken);
        return request.exchange((req, response) -> new Response(response.getStatusCode().value(), response.bodyTo(Map.class)));
    }

    private List<Long> recommendedIds(Response response) {
        @SuppressWarnings("unchecked") var rows = (List<Map<String, Object>>) response.data().get("posts");
        return rows.stream().map(row -> ((Number) row.get("id")).longValue()).toList();
    }

    long post(String title, String description, long price, ProductCategory category, ProductCondition condition, ProductStatus status) {
        var post = Post.builder().user(seller).title(title).description(description).price(price)
            .productCategory(category).productCondition(condition).productStatus(status).build();
        post.addImage("posts/" + seller.getId() + "/second.png", 1);
        post.addImage("posts/" + seller.getId() + "/first.png", 0);
        return posts.save(post).getId();
    }

    Response search(Map<String, String> params) {
        String query = params.entrySet().stream().map(e -> e.getKey() + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8)).collect(Collectors.joining("&"));
        return request(HttpMethod.GET, "/api/posts" + (query.isEmpty() ? "" : "?" + query), token);
    }

    Response request(HttpMethod method, String path, String accessToken) {
        var request = RestClient.create().method(method).uri(java.net.URI.create("http://127.0.0.1:" + port + path));
        if (accessToken != null) request.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken);
        return request.exchange((req, response) -> new Response(response.getStatusCode().value(), response.bodyTo(Map.class)));
    }

    static void error(Response response, int status, String code) {
        assertThat(response.status()).isEqualTo(status);
        assertThat(response.body()).containsEntry("success", false);
        @SuppressWarnings("unchecked") var error = (Map<String, Object>) response.body().get("error");
        assertThat(error).containsEntry("code", code);
    }

    record Response(int status, Map<String, Object> body) {
        @SuppressWarnings("unchecked") Map<String, Object> data() { return (Map<String, Object>) body.get("data"); }
        @SuppressWarnings("unchecked") List<Map<String, Object>> rows() { return (List<Map<String, Object>>) data().get("content"); }
        List<Long> ids() { return rows().stream().map(r -> ((Number) r.get("id")).longValue()).toList(); }
    }
}
