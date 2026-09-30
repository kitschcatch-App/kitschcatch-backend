// 실제 HTTP·JWT·DB로 관심 매장의 사용자 격리·동시성·조회 연동을 공통 검증한다.
package com.kitschcatch.backend.domain.store;

import static org.assertj.core.api.Assertions.assertThat;

import com.kitschcatch.backend.domain.store.entity.Store;
import com.kitschcatch.backend.domain.store.repository.StoreRepository;
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

abstract class StoreFavoriteHttpContract {
    @LocalServerPort int port;
    @Autowired StoreRepository stores;
    @Autowired UserRepository users;
    @Autowired JwtTokenProvider tokens;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManagerFactory entityManagerFactory;
    User user;
    User other;
    Store store;
    String token;
    String otherToken;

    @BeforeEach
    void resetData() {
        jdbc.update("DELETE FROM store_favorites");
        jdbc.update("DELETE FROM store_business_hours");
        jdbc.update("DELETE FROM stores");
        jdbc.update("DELETE FROM users");
        user = user();
        other = user();
        token = tokens.createAccessToken(user.getId());
        otherToken = tokens.createAccessToken(other.getId());
        store = store("첫 매장");
    }

    @Test
    void repeatedRegistrationKeepsOneRelationAndOriginalTimestamp() {
        var result = change("POST", store.getId(), token);
        assertThat(result.status()).isEqualTo(200);
        assertThat(result.data()).containsEntry("favorited", true).containsEntry("favoriteCount", 1);
        assertThat(((Number) result.data().get("storeId")).longValue()).isEqualTo(store.getId());
        var original = jdbc.queryForMap("SELECT id, created_at FROM store_favorites");
        assertThat(change("POST", store.getId(), token).data()).containsEntry("favoriteCount", 1);
        assertThat(jdbc.queryForMap("SELECT id, created_at FROM store_favorites")).isEqualTo(original);
        assertThat(user.isProfileRegistered()).isFalse();
    }

    @Test
    void repeatedRemovalOnlyRemovesCurrentUserAndCountsRemainingUsers() {
        change("POST", store.getId(), token);
        assertThat(change("POST", store.getId(), otherToken).data()).containsEntry("favoriteCount", 2);
        for (int i = 0; i < 2; i++) {
            var result = change("DELETE", store.getId(), token);
            assertThat(result.status()).isEqualTo(200);
            assertThat(result.data()).containsEntry("favorited", false).containsEntry("favoriteCount", 1);
        }
        assertThat(get("/api/users/me/favorite-stores", token).rows("content")).isEmpty();
        assertThat(get("/api/users/me/favorite-stores", otherToken).ids("content")).containsExactly(store.getId());
    }

    @Test
    void emptyAndOutOfRangePagesAreSuccessful() {
        var result = get("/api/users/me/favorite-stores", token);
        assertThat(result.status()).isEqualTo(200);
        assertThat(result.data()).containsEntry("page", 0).containsEntry("size", 20)
            .containsEntry("totalElements", 0).containsEntry("totalPages", 0);
        assertThat(result.rows("content")).isEmpty();
        change("POST", store.getId(), token);
        var end = get("/api/users/me/favorite-stores?page=10000&size=100", token);
        assertThat(end.status()).isEqualTo(200);
        assertThat(end.rows("content")).isEmpty();
        assertThat(end.data()).containsEntry("totalElements", 1);
    }

    @Test
    void ownListUsesRecentRegistrationAndIdTieBreakerWithPageTotals() {
        var second = store("다음 매장");
        var third = store("마지막 매장");
        change("POST", third.getId(), otherToken);
        change("POST", store.getId(), token);
        change("POST", second.getId(), token);
        jdbc.update("UPDATE store_favorites SET created_at = TIMESTAMP '2026-09-30 00:00:00'");
        var firstPage = get("/api/users/me/favorite-stores?size=1", token);
        assertThat(firstPage.ids("content")).containsExactly(second.getId());
        assertThat(firstPage.data()).containsEntry("totalElements", 2).containsEntry("totalPages", 2);
        assertThat(firstPage.rows("content").getFirst()).containsEntry("favorited", true)
            .containsEntry("distanceMeters", null).containsEntry("thumbnailUrl", null)
            .containsEntry("latitude", 37.5665).containsEntry("longitude", 126.978);
        assertThat(get("/api/users/me/favorite-stores?size=1&page=1", token).ids("content"))
            .containsExactly(store.getId());
        // 같은 ID 순서에 의존하지 않고 등록 시각을 우선 정렬한다.
        jdbc.update("UPDATE store_favorites SET created_at = TIMESTAMP '2026-10-01 00:00:00' WHERE user_id=? AND store_id=?", user.getId(), store.getId());
        assertThat(get("/api/users/me/favorite-stores?size=1", token).ids("content")).containsExactly(store.getId());
    }

    @Test
    void reRegisteringAfterRemovalMakesTheStoreRecentAgain() {
        var second = store("다음 매장");
        change("POST", store.getId(), token);
        change("POST", second.getId(), token);
        change("DELETE", store.getId(), token);
        change("POST", store.getId(), token);
        assertThat(get("/api/users/me/favorite-stores", token).ids("content"))
            .containsExactly(store.getId(), second.getId());
    }

    @Test
    void suppliedUserIdCannotReadOrModifyAnotherUsersFavorites() {
        assertThat(request("POST", favoritePath(store.getId()) + "?userId=" + other.getId(), token).status()).isEqualTo(200);
        assertThat(get("/api/users/me/favorite-stores?userId=" + user.getId(), otherToken).rows("content")).isEmpty();
        request("DELETE", favoritePath(store.getId()) + "?userId=" + user.getId(), otherToken);
        assertThat(get("/api/users/me/favorite-stores", token).ids("content")).containsExactly(store.getId());
    }

    @Test
    void existingReadEndpointsReflectOnlyRequestingUsersFavorites() {
        var unselected = store("미등록 매장");
        for (boolean selected : List.of(false, true, false)) {
            change(selected ? "POST" : "DELETE", store.getId(), token);
            assertThat(get("/api/stores/" + store.getId(), token).data()).containsEntry("favorited", selected);
            assertThat(get("/api/stores/" + store.getId(), otherToken).data()).containsEntry("favorited", false);
            for (String path : List.of("/api/stores?region=서울", "/api/stores/nearby?latitude=37.5665&longitude=126.978")) {
                String rows = path.contains("nearby") ? "stores" : "content";
                var result = get(path, token);
                assertThat(result.ids(rows)).containsExactly(store.getId(), unselected.getId());
                assertThat(result.rows(rows).getFirst()).containsEntry("favorited", selected);
                assertThat(result.rows(rows).get(1)).containsEntry("favorited", false);
                assertThat(get(path, otherToken).rows(rows)).allSatisfy(row -> assertThat(row).containsEntry("favorited", false));
            }
        }
    }

    @Test
    void favoriteFlagsAreAddedToEveryPageIncludingMaximumSize() {
        for (int i = 0; i < 100; i++) store("매장" + i);
        var last = stores.findAll().stream().max(java.util.Comparator.comparing(Store::getId)).orElseThrow();
        change("POST", last.getId(), token);
        for (String path : List.of("/api/stores?size=100&page=1", "/api/stores/nearby?latitude=37.5665&longitude=126.978&size=100&page=1")) {
            String rows = path.contains("nearby") ? "stores" : "content";
            var result = get(path, token);
            assertThat(result.ids(rows)).containsExactly(last.getId());
            assertThat(result.rows(rows).getFirst()).containsEntry("favorited", true);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"POST", "DELETE", "GET"})
    void allFavoriteEndpointsRequireAccessToken(String method) {
        String path = method.equals("GET") ? "/api/users/me/favorite-stores" : favoritePath(store.getId());
        for (String badToken : new String[]{null, "invalid", tokens.createRefreshToken(user.getId())}) {
            assertError(request(method, path, badToken), 401, "AUTH_004");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"POST", "DELETE", "GET"})
    void deletedUserIsRejected(String method) {
        String path = method.equals("GET") ? "/api/users/me/favorite-stores" : favoritePath(store.getId());
        assertError(request(method, path, tokens.createAccessToken(Long.MAX_VALUE)), 404, "USER_001");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM store_favorites", Long.class)).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "abc", "1.5", "9223372036854775808"})
    void malformedStoreIdsAreRejected(String id) {
        for (String method : List.of("POST", "DELETE")) {
            assertError(request(method, "/api/stores/" + id + "/favorites", token), 400, "COMMON_002");
        }
    }

    @Test
    void nonexistentStoreIsRejectedForRegisterAndRemove() {
        for (String method : List.of("POST", "DELETE")) assertError(change(method, Long.MAX_VALUE, token), 404, "STORE_001");
    }

    @ParameterizedTest
    @ValueSource(strings = {"page=-1", "page=10001", "page=no", "page=2147483648", "size=0", "size=101", "size=1.5"})
    void invalidPaginationMatchesExistingStoreApi(String query) {
        assertError(get("/api/users/me/favorite-stores?" + query, token), 400, "COMMON_001");
    }

    @Test
    void concurrentRegistrationAndRemovalRemainIdempotent() throws Exception {
        var registered = concurrent(java.util.Collections.nCopies(8, "POST"), token);
        assertThat(registered).allSatisfy(result -> {
            assertThat(result.status()).isEqualTo(200);
            assertThat(result.data()).containsEntry("favorited", true).containsEntry("favoriteCount", 1);
        });
        assertThat(jdbc.queryForObject("SELECT count(*) FROM store_favorites", Long.class)).isEqualTo(1L);
        var removed = concurrent(java.util.Collections.nCopies(8, "DELETE"), token);
        assertThat(removed).allSatisfy(result -> {
            assertThat(result.status()).isEqualTo(200);
            assertThat(result.data()).containsEntry("favorited", false).containsEntry("favoriteCount", 0);
        });
        assertThat(jdbc.queryForObject("SELECT count(*) FROM store_favorites", Long.class)).isZero();
    }

    @Test
    void mixedConcurrentChangesAreSuccessfulAndPreserveOtherUsersRelation() throws Exception {
        change("POST", store.getId(), otherToken);
        var results = concurrent(List.of("POST", "DELETE", "POST", "DELETE", "POST", "DELETE", "POST", "DELETE"), token);
        assertThat(results).allSatisfy(result -> assertThat(result.status()).isEqualTo(200));
        assertThat(get("/api/users/me/favorite-stores", otherToken).ids("content")).containsExactly(store.getId());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM store_favorites WHERE user_id=?", Long.class, user.getId())).isBetween(0L, 1L);
        assertThat(change("POST", store.getId(), token).data()).containsEntry("favoriteCount", 2);
        assertThat(change("DELETE", store.getId(), token).data()).containsEntry("favoriteCount", 1);
    }

    @Test
    void deletingStoreOrUserCleansUpRelations() {
        change("POST", store.getId(), token);
        jdbc.update("DELETE FROM users WHERE id=?", user.getId());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM store_favorites", Long.class)).isZero();
        change("POST", store.getId(), otherToken);
        jdbc.update("DELETE FROM stores WHERE id=?", store.getId());
        assertThat(get("/api/users/me/favorite-stores", otherToken).rows("content")).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM store_favorites", Long.class)).isZero();
    }

    @Test
    void pageQueriesDoNotLoadEachStoreOrItsBusinessHoursSeparately() {
        for (int i = 0; i < 20; i++) change("POST", store("관심 매장" + i).getId(), token);
        var statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        for (String path : List.of("/api/users/me/favorite-stores", "/api/stores", "/api/stores/nearby?latitude=37.5665&longitude=126.978")) {
            statistics.clear();
            var result = get(path, token);
            assertThat(result.status()).isEqualTo(200);
            assertThat(result.rows(path.contains("nearby") ? "stores" : "content")).hasSize(20);
            assertThat(statistics.getPrepareStatementCount()).as(path).isBetween(1L, 4L);
            assertThat(statistics.getCollectionFetchCount()).as(path).isZero();
            assertThat(statistics.getEntityFetchCount()).as(path).isZero();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void swaggerDocumentsAuthenticationAndAllThreeOperations() {
        var response = get("/v3/api-docs", null);
        var paths = (Map<String, Map<String, Map<String, Object>>>) response.body().get("paths");
        assertThat(paths.get("/api/stores/{storeId}/favorites")).containsOnlyKeys("post", "delete");
        for (var operation : paths.get("/api/stores/{storeId}/favorites").values()) {
            assertThat(operation.get("security").toString()).contains("bearerAuth");
            assertThat((Map<String, Object>) operation.get("responses")).containsKey("200").doesNotContainKey("409");
        }
        var list = paths.get("/api/users/me/favorite-stores").get("get");
        assertThat(list.get("security").toString()).contains("bearerAuth");
        assertThat(list.get("parameters").toString()).contains("page", "size").doesNotContain("userId");
        assertThat(list.get("description").toString()).contains("distanceMeters", "null");
    }

    private List<StoreHttpContract.Response> concurrent(List<String> methods, String accessToken) throws Exception {
        var barrier = new CyclicBarrier(methods.size());
        try (var executor = Executors.newFixedThreadPool(methods.size())) {
            var tasks = new ArrayList<Callable<StoreHttpContract.Response>>();
            for (String method : methods) tasks.add(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                return change(method, store.getId(), accessToken);
            });
            var results = new ArrayList<StoreHttpContract.Response>();
            for (var future : executor.invokeAll(tasks, 30, TimeUnit.SECONDS)) results.add(future.get());
            return results;
        }
    }

    private User user() {
        String id = UUID.randomUUID().toString();
        return users.saveAndFlush(User.builder().nickname("검증사용자").email(id + "@example.test")
            .authProvider(AuthProvider.KAKAO).providerUserId(id).build());
    }

    private Store store(String name) {
        return stores.saveAndFlush(Store.builder().name(name).region("서울").address("검증용 가상 주소")
            .latitude(37.5665).longitude(126.978).build());
    }

    private String favoritePath(long id) { return "/api/stores/" + id + "/favorites"; }
    private StoreHttpContract.Response change(String method, long id, String accessToken) {
        return request(method, favoritePath(id), accessToken);
    }
    private StoreHttpContract.Response get(String path, String accessToken) { return request("GET", path, accessToken); }
    private StoreHttpContract.Response request(String method, String path, String accessToken) {
        var request = RestClient.create("http://127.0.0.1:" + port).method(HttpMethod.valueOf(method)).uri(path);
        if (accessToken != null) request.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken);
        return request.exchange((req, res) -> new StoreHttpContract.Response(res.getStatusCode().value(), res.bodyTo(Map.class)));
    }
    private void assertError(StoreHttpContract.Response response, int status, String code) {
        assertThat(response.status()).isEqualTo(status);
        assertThat(response.body()).containsEntry("success", false).doesNotContainKey("data");
        assertThat(((Map<?, ?>) response.body().get("error")).get("code")).isEqualTo(code);
    }
}
