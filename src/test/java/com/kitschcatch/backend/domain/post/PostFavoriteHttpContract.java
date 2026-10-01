// 실제 HTTP·JWT·DB로 관심 상품의 사용자 격리·동시성·조회 연동을 공통 검증한다.
package com.kitschcatch.backend.domain.post;

import static org.assertj.core.api.Assertions.assertThat;

import com.kitschcatch.backend.domain.post.entity.Post;
import com.kitschcatch.backend.domain.post.entity.ProductCategory;
import com.kitschcatch.backend.domain.post.entity.ProductCondition;
import com.kitschcatch.backend.domain.post.entity.ProductStatus;
import com.kitschcatch.backend.domain.post.repository.PostRepository;
import com.kitschcatch.backend.domain.user.entity.AuthProvider;
import com.kitschcatch.backend.domain.user.entity.User;
import com.kitschcatch.backend.domain.user.repository.UserRepository;
import com.kitschcatch.backend.global.security.JwtTokenProvider;
import jakarta.persistence.EntityManagerFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;

abstract class PostFavoriteHttpContract {
    @LocalServerPort int port;
    @Autowired PostRepository posts;
    @Autowired UserRepository users;
    @Autowired JwtTokenProvider tokens;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManagerFactory entityManagerFactory;
    User user;
    User other;
    User seller;
    Post post;
    String token;
    String otherToken;

    @BeforeEach
    void resetData() {
        jdbc.update("DELETE FROM post_favorites");
        jdbc.update("DELETE FROM post_images");
        jdbc.update("DELETE FROM posts");
        jdbc.update("DELETE FROM users");
        user = user();
        other = user();
        seller = user();
        token = tokens.createAccessToken(user.getId());
        otherToken = tokens.createAccessToken(other.getId());
        post = post("첫 상품");
    }

    @Test
    void repeatedRegistrationKeepsOneRelationAndOriginalTimestamp() {
        var result = change("POST", post.getId(), token);
        assertThat(result.status()).isEqualTo(200);
        assertThat(result.data()).containsEntry("favorited", true).containsEntry("favoriteCount", 1);
        assertThat(((Number) result.data().get("postId")).longValue()).isEqualTo(post.getId());
        var original = jdbc.queryForMap("SELECT id, created_at FROM post_favorites");
        assertThat(change("POST", post.getId(), token).data()).containsEntry("favoriteCount", 1);
        assertThat(jdbc.queryForMap("SELECT id, created_at FROM post_favorites")).isEqualTo(original);
        assertThat(user.isProfileRegistered()).isFalse();
    }

    @Test
    void repeatedRemovalOnlyRemovesCurrentUserAndCountsRemainingUsers() {
        change("POST", post.getId(), token);
        assertThat(change("POST", post.getId(), otherToken).data()).containsEntry("favoriteCount", 2);
        for (int i = 0; i < 2; i++) {
            var result = change("DELETE", post.getId(), token);
            assertThat(result.status()).isEqualTo(200);
            assertThat(result.data()).containsEntry("favorited", false).containsEntry("favoriteCount", 1);
        }
        assertThat(get("/api/users/me/favorite-posts", token).rows("content")).isEmpty();
        assertThat(get("/api/users/me/favorite-posts", otherToken).ids("content")).containsExactly(post.getId());
    }

    @Test
    void emptyAndOutOfRangePagesAreSuccessful() {
        var result = get("/api/users/me/favorite-posts", token);
        assertThat(result.status()).isEqualTo(200);
        assertThat(result.data()).containsEntry("page", 0).containsEntry("size", 20)
            .containsEntry("totalElements", 0).containsEntry("totalPages", 0);
        assertThat(result.rows("content")).isEmpty();
        change("POST", post.getId(), token);
        var end = get("/api/users/me/favorite-posts?page=10000&size=100", token);
        assertThat(end.status()).isEqualTo(200);
        assertThat(end.rows("content")).isEmpty();
        assertThat(end.data()).containsEntry("totalElements", 1);
    }

    @Test
    void ownListUsesRecentRegistrationAndIdTieBreakerWithPageTotals() {
        var second = post("다음 상품");
        var third = post("마지막 상품");
        change("POST", third.getId(), otherToken);
        change("POST", post.getId(), token);
        change("POST", second.getId(), token);
        jdbc.update("UPDATE post_favorites SET created_at = TIMESTAMP '2026-09-30 00:00:00'");
        var firstPage = get("/api/users/me/favorite-posts?size=1", token);
        assertThat(firstPage.ids("content")).containsExactly(second.getId());
        assertThat(firstPage.data()).containsEntry("totalElements", 2).containsEntry("totalPages", 2);
        assertThat(firstPage.rows("content").getFirst()).containsEntry("favorited", true)
            .containsEntry("thumbnailUrl", null).containsEntry("productStatus", "ON_SALE");
        assertThat(get("/api/users/me/favorite-posts?size=1&page=1", token).ids("content"))
            .containsExactly(post.getId());
        // 같은 ID 순서에 의존하지 않고 등록 시각을 우선 정렬한다.
        jdbc.update("UPDATE post_favorites SET created_at = TIMESTAMP '2026-10-01 00:00:00' WHERE user_id=? AND post_id=?", user.getId(), post.getId());
        assertThat(get("/api/users/me/favorite-posts?size=1", token).ids("content")).containsExactly(post.getId());
    }

    @Test
    void reRegisteringAfterRemovalMakesThePostRecentAgain() {
        var second = post("다음 상품");
        change("POST", post.getId(), token);
        change("POST", second.getId(), token);
        change("DELETE", post.getId(), token);
        change("POST", post.getId(), token);
        assertThat(get("/api/users/me/favorite-posts", token).ids("content"))
            .containsExactly(post.getId(), second.getId());
    }

    @Test
    void suppliedUserIdCannotReadOrModifyAnotherUsersFavorites() {
        assertThat(request("POST", favoritePath(post.getId()) + "?userId=" + other.getId(), token).status()).isEqualTo(200);
        assertThat(get("/api/users/me/favorite-posts?userId=" + user.getId(), otherToken).rows("content")).isEmpty();
        request("DELETE", favoritePath(post.getId()) + "?userId=" + user.getId(), otherToken);
        assertThat(get("/api/users/me/favorite-posts", token).ids("content")).containsExactly(post.getId());
    }

    @ParameterizedTest
    @ValueSource(strings = {"POST", "DELETE", "GET"})
    void allFavoriteEndpointsRequireAccessToken(String method) {
        String path = method.equals("GET") ? "/api/users/me/favorite-posts" : favoritePath(post.getId());
        for (String badToken : new String[]{null, "invalid", tokens.createRefreshToken(user.getId())}) {
            assertError(request(method, path, badToken), 401, "AUTH_004");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"POST", "DELETE", "GET"})
    void deletedUserIsRejected(String method) {
        String path = method.equals("GET") ? "/api/users/me/favorite-posts" : favoritePath(post.getId());
        assertError(request(method, path, tokens.createAccessToken(Long.MAX_VALUE)), 404, "USER_001");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM post_favorites", Long.class)).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "abc", "1.5", "9223372036854775808"})
    void malformedPostIdsAreRejected(String id) {
        for (String method : List.of("POST", "DELETE")) {
            assertError(request(method, "/api/posts/" + id + "/favorites", token), 400, "COMMON_002");
        }
    }

    @Test
    void nonexistentPostIsRejectedForRegisterAndRemove() {
        for (String method : List.of("POST", "DELETE")) assertError(change(method, Long.MAX_VALUE, token), 404, "POST_001");
    }

    @ParameterizedTest
    @ValueSource(strings = {"page=-1", "page=10001", "page=no", "page=2147483648", "size=0", "size=101", "size=1.5"})
    void invalidPaginationMatchesExistingPostApi(String query) {
        assertError(get("/api/users/me/favorite-posts?" + query, token), 400, "COMMON_001");
    }

    @Test
    void concurrentRegistrationAndRemovalRemainIdempotent() throws Exception {
        var registered = concurrent(java.util.Collections.nCopies(8, "POST"), token);
        assertThat(registered).allSatisfy(result -> {
            assertThat(result.status()).isEqualTo(200);
            assertThat(result.data()).containsEntry("favorited", true).containsEntry("favoriteCount", 1);
        });
        assertThat(jdbc.queryForObject("SELECT count(*) FROM post_favorites", Long.class)).isEqualTo(1L);
        var removed = concurrent(java.util.Collections.nCopies(8, "DELETE"), token);
        assertThat(removed).allSatisfy(result -> {
            assertThat(result.status()).isEqualTo(200);
            assertThat(result.data()).containsEntry("favorited", false).containsEntry("favoriteCount", 0);
        });
        assertThat(jdbc.queryForObject("SELECT count(*) FROM post_favorites", Long.class)).isZero();
    }

    @Test
    void mixedConcurrentChangesAreSuccessfulAndPreserveOtherUsersRelation() throws Exception {
        change("POST", post.getId(), otherToken);
        var results = concurrent(List.of("POST", "DELETE", "POST", "DELETE", "POST", "DELETE", "POST", "DELETE"), token);
        assertThat(results).allSatisfy(result -> assertThat(result.status()).isEqualTo(200));
        assertThat(get("/api/users/me/favorite-posts", otherToken).ids("content")).containsExactly(post.getId());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM post_favorites WHERE user_id=?", Long.class, user.getId())).isBetween(0L, 1L);
        assertThat(change("POST", post.getId(), token).data()).containsEntry("favoriteCount", 2);
        assertThat(change("DELETE", post.getId(), token).data()).containsEntry("favoriteCount", 1);
    }

    @Test
    void differentUsersCanConcurrentlyRegisterAndRemoveSamePost() throws Exception {
        var accessTokens = new ArrayList<String>();
        for (int i = 0; i < 8; i++) accessTokens.add(tokens.createAccessToken(user().getId()));
        var registered = concurrent(java.util.Collections.nCopies(8, "POST"), accessTokens);
        assertThat(registered).allSatisfy(result -> assertThat(result.status()).isEqualTo(200));
        assertThat(registered.stream().map(result -> ((Number) result.data().get("favoriteCount")).longValue()))
            .containsExactlyInAnyOrder(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM post_favorites", Long.class)).isEqualTo(8L);
        var removed = concurrent(java.util.Collections.nCopies(8, "DELETE"), accessTokens);
        assertThat(removed).allSatisfy(result -> assertThat(result.status()).isEqualTo(200));
        assertThat(removed.stream().map(result -> ((Number) result.data().get("favoriteCount")).longValue()))
            .containsExactlyInAnyOrder(0L, 1L, 2L, 3L, 4L, 5L, 6L, 7L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM post_favorites", Long.class)).isZero();
    }

    @Test
    void sellerCanFavoriteOwnPostWithoutChangingProductState() {
        String sellerToken = tokens.createAccessToken(seller.getId());
        assertThat(change("POST", post.getId(), sellerToken).status()).isEqualTo(200);
        assertThat(get("/api/users/me/favorite-posts", sellerToken).ids("content")).containsExactly(post.getId());
        assertThat(posts.findById(post.getId()).orElseThrow().getProductStatus()).isEqualTo(ProductStatus.ON_SALE);
    }

    @Test
    void softDeletedPostsAreHiddenButRemovableAndSoldPostsKeepTheirStatus() {
        var sold = post("판매 완료 상품");
        var reserved = post("예약 상품");
        change("POST", post.getId(), token);
        jdbc.update("UPDATE posts SET product_status='SOLD_OUT' WHERE id=?", sold.getId());
        jdbc.update("UPDATE posts SET product_status='RESERVED' WHERE id=?", reserved.getId());
        assertThat(change("POST", sold.getId(), token).status()).isEqualTo(200);
        assertThat(change("POST", reserved.getId(), token).status()).isEqualTo(200);
        jdbc.update("UPDATE posts SET deleted_at=CURRENT_TIMESTAMP WHERE id=?", post.getId());
        var result = get("/api/users/me/favorite-posts", token);
        assertThat(result.ids("content")).containsExactly(reserved.getId(), sold.getId());
        assertThat(result.rows("content").getFirst()).containsEntry("productStatus", "RESERVED");
        assertThat(result.rows("content").get(1)).containsEntry("productStatus", "SOLD_OUT");
        assertThat(result.data()).containsEntry("totalElements", 2);
        assertError(change("POST", post.getId(), token), 404, "POST_001");
        assertThat(change("DELETE", post.getId(), token).status()).isEqualTo(200);
        assertThat(change("DELETE", post.getId(), token).data()).containsEntry("favoriteCount", 0);
    }

    @Test
    void deletingPostOrFavoriteOwnerCleansUpRelations() {
        change("POST", post.getId(), token);
        jdbc.update("DELETE FROM users WHERE id=?", user.getId());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM post_favorites", Long.class)).isZero();
        change("POST", post.getId(), otherToken);
        jdbc.update("DELETE FROM posts WHERE id=?", post.getId());
        assertThat(get("/api/users/me/favorite-posts", otherToken).rows("content")).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM post_favorites", Long.class)).isZero();
    }

    @Test
    void listLoadsSellersAndThumbnailsWithoutNPlusOneAtMaximumPageSize() {
        for (int i = 0; i < 100; i++) {
            seller = user();
            var item = post("관심 상품" + i);
            jdbc.update("INSERT INTO post_images(post_id,object_key,sort_order) VALUES (?,?,?)", item.getId(), "later.jpg", 1);
            jdbc.update("INSERT INTO post_images(post_id,object_key,sort_order) VALUES (?,?,?)", item.getId(), "first.jpg", 0);
            jdbc.update("INSERT INTO post_images(post_id,object_key,sort_order) VALUES (?,?,?)", item.getId(), "tie.jpg", 0);
            change("POST", item.getId(), token);
        }
        var statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();
        var result = get("/api/users/me/favorite-posts?size=100", token);
        assertThat(result.rows("content")).hasSize(100).allSatisfy(row ->
            assertThat(row).containsEntry("thumbnailUrl", "https://images.example.test/first.jpg")
                .containsEntry("sellerNickname", "검증사용자"));
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(4);
        assertThat(statistics.getCollectionFetchCount()).isZero();
        assertThat(statistics.getEntityFetchCount()).isZero();
    }

    @Test
    @SuppressWarnings("unchecked")
    void swaggerDocumentsAuthenticationAndAllThreeOperations() {
        var response = get("/v3/api-docs", null);
        var paths = (Map<String, Map<String, Map<String, Object>>>) response.body().get("paths");
        assertThat(paths.get("/api/posts/{postId}/favorites")).containsOnlyKeys("post", "delete");
        for (var operation : paths.get("/api/posts/{postId}/favorites").values()) {
            assertThat(operation.get("security").toString()).contains("bearerAuth");
            assertThat((Map<String, Object>) operation.get("responses")).containsKey("200").doesNotContainKey("409");
        }
        var list = paths.get("/api/users/me/favorite-posts").get("get");
        assertThat(list.get("security").toString()).contains("bearerAuth");
        assertThat(list.get("parameters").toString()).contains("page", "size").doesNotContain("userId");
        assertThat(list.get("description").toString()).contains("productStatus", "thumbnailUrl");
    }

    private List<Response> concurrent(List<String> methods, String accessToken) throws Exception {
        return concurrent(methods, java.util.Collections.nCopies(methods.size(), accessToken));
    }

    private List<Response> concurrent(List<String> methods, List<String> accessTokens) throws Exception {
        var barrier = new CyclicBarrier(methods.size());
        try (var executor = Executors.newFixedThreadPool(methods.size())) {
            var tasks = new ArrayList<Callable<Response>>();
            for (int i = 0; i < methods.size(); i++) {
                String method = methods.get(i);
                String accessToken = accessTokens.get(i);
                tasks.add(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    return change(method, post.getId(), accessToken);
                });
            }
            var results = new ArrayList<Response>();
            for (var future : executor.invokeAll(tasks, 30, TimeUnit.SECONDS)) results.add(future.get());
            return results;
        }
    }

    private User user() {
        String id = UUID.randomUUID().toString();
        return users.saveAndFlush(User.builder().nickname("검증사용자").email(id + "@example.test")
            .authProvider(AuthProvider.KAKAO).providerUserId(id).build());
    }

    private Post post(String name) {
        return posts.saveAndFlush(Post.builder().user(seller).title(name).description("검증용 상품")
            .price(1000L).productCategory(ProductCategory.GOODS).productCondition(ProductCondition.NEW)
            .productStatus(ProductStatus.ON_SALE).build());
    }

    private String favoritePath(long id) { return "/api/posts/" + id + "/favorites"; }
    private Response change(String method, long id, String accessToken) {
        return request(method, favoritePath(id), accessToken);
    }
    private Response get(String path, String accessToken) { return request("GET", path, accessToken); }
    private Response request(String method, String path, String accessToken) {
        var request = RestClient.create("http://127.0.0.1:" + port).method(HttpMethod.valueOf(method)).uri(path);
        if (accessToken != null) request.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken);
        return request.exchange((req, res) -> new Response(res.getStatusCode().value(), res.bodyTo(Map.class)));
    }
    private void assertError(Response response, int status, String code) {
        assertThat(response.status()).isEqualTo(status);
        assertThat(response.body()).containsEntry("success", false).doesNotContainKey("data");
        assertThat(((Map<?, ?>) response.body().get("error")).get("code")).isEqualTo(code);
    }
    record Response(int status, Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        Map<String, Object> data() { return (Map<String, Object>) body.get("data"); }
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows(String key) { return (List<Map<String, Object>>) data().get(key); }
        List<Long> ids(String key) { return rows(key).stream().map(row -> ((Number) row.get("id")).longValue()).toList(); }
    }
}
