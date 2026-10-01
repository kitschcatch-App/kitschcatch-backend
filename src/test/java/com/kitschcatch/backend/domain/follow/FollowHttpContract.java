// 실제 HTTP와 JWT로 방향성·권한·동시성·페이지·비노출·쿼리 수를 검증한다.
package com.kitschcatch.backend.domain.follow;

import static org.assertj.core.api.Assertions.assertThat;

import com.kitschcatch.backend.domain.auth.oidc.KakaoOidcUser;
import com.kitschcatch.backend.domain.auth.service.KakaoUserService;
import com.kitschcatch.backend.domain.user.entity.AuthProvider;
import com.kitschcatch.backend.domain.user.entity.User;
import com.kitschcatch.backend.domain.user.repository.UserRepository;
import com.kitschcatch.backend.global.security.JwtTokenProvider;
import jakarta.persistence.EntityManagerFactory;
import java.util.*;
import java.util.concurrent.*;
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

abstract class FollowHttpContract {
    @LocalServerPort int port;
    @Autowired UserRepository users;
    @Autowired JwtTokenProvider tokens;
    @Autowired KakaoUserService kakaoUsers;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManagerFactory entityManagerFactory;
    User actor;
    User target;
    User third;
    String token;
    String targetToken;

    @BeforeEach
    void reset() {
        jdbc.update("DELETE FROM user_follows");
        jdbc.update("DELETE FROM users");
        actor = user(); target = user(); third = user();
        token = tokens.createAccessToken(actor.getId());
        targetToken = tokens.createAccessToken(target.getId());
    }

    @Test
    void repeatsKeepOriginalRelationAndRemovalIsIdempotent() {
        var result = change("POST", target.getId(), token);
        assertThat(result.status()).isEqualTo(200);
        assertThat(result.data()).containsEntry("following", true);
        assertThat(((Number) result.data().get("userId")).longValue()).isEqualTo(target.getId());
        var original = jdbc.queryForMap("SELECT id, created_at FROM user_follows");
        assertThat(change("POST", target.getId(), token).status()).isEqualTo(200);
        assertThat(jdbc.queryForMap("SELECT id, created_at FROM user_follows")).isEqualTo(original);
        for (int i = 0; i < 2; i++) {
            var removed = change("DELETE", target.getId(), token);
            assertThat(removed.status()).isEqualTo(200);
            assertThat(removed.data()).containsEntry("following", false);
        }
        assertThat(count()).isZero();
        assertThat(actor.isProfileRegistered()).isFalse();
    }

    @Test
    void directionsAndThirdPartyListsAreIndependent() {
        change("POST", target.getId(), token);
        change("POST", actor.getId(), targetToken);
        change("POST", target.getId(), tokens.createAccessToken(third.getId()));
        assertThat(list(target.getId(), "followers", token).ids()).containsExactly(third.getId(), actor.getId());
        assertThat(list(actor.getId(), "followings", targetToken).ids()).containsExactly(target.getId());
        assertThat(list(target.getId(), "followings", token).ids()).containsExactly(actor.getId());
        change("DELETE", target.getId(), token);
        assertThat(list(target.getId(), "followings", token).ids()).containsExactly(actor.getId());
        assertThat(list(target.getId(), "followers", token).ids()).containsExactly(third.getId());
    }

    @Test
    void callerCannotOverrideAuthenticatedActorUsingQueryOrBody() {
        var registered = request("POST", path(target.getId(), "follow") + "?actorId=" + third.getId(), token,
            Map.of("userId", third.getId(), "followerId", third.getId()));
        assertThat(registered.status()).isEqualTo(200);
        assertThat(list(actor.getId(), "followings", token).ids()).containsExactly(target.getId());
        assertThat(list(third.getId(), "followings", token).ids()).isEmpty();
        request("DELETE", path(target.getId(), "follow") + "?actorId=" + actor.getId(), targetToken, Map.of("followerId", actor.getId()));
        assertThat(list(actor.getId(), "followings", token).ids()).containsExactly(target.getId());
    }

    @Test
    void publicRowsExcludeEmailProviderKeysAndRegistrationMetadata() {
        jdbc.update("UPDATE users SET profile_image_key=? WHERE id=?", "profiles/" + actor.getId() + "/avatar.png", actor.getId());
        change("POST", target.getId(), token);
        var row = list(target.getId(), "followers", targetToken).rows().getFirst();
        assertThat(row).containsOnlyKeys("id", "nickname", "profileImageUrl");
        assertThat(row).containsEntry("profileImageUrl", "https://images.example.test/profiles/" + actor.getId() + "/avatar.png");
        var following = list(actor.getId(), "followings", token).rows().getFirst();
        assertThat(following).containsOnlyKeys("id", "nickname", "profileImageUrl").containsEntry("profileImageUrl", null);
    }

    @Test
    void unregisteredKakaoNicknameCannotExposeProviderSubjectOrSocialNickname() {
        String subject = UUID.randomUUID().toString();
        var kakao = kakaoUsers.createKakaoUser(new KakaoOidcUser(subject, subject + "@example.test", null));
        assertThat(kakao.getNickname()).isEqualTo("kakao-" + subject);
        String kakaoToken = tokens.createAccessToken(kakao.getId());
        change("POST", actor.getId(), kakaoToken);
        change("POST", kakao.getId(), token);
        for (String kind : List.of("followers", "followings")) {
            var result = list(actor.getId(), kind, token);
            assertThat(result.rows().getFirst()).containsEntry("nickname", "사용자");
            assertThat(result.body().toString()).doesNotContain(subject, kakao.getEmail());
        }
        jdbc.update("UPDATE users SET nickname=? WHERE id=?", "비공개소셜이름", kakao.getId());
        assertThat(list(actor.getId(), "followers", token).rows().getFirst()).containsEntry("nickname", "사용자");
        kakao.registerProfile("공개닉네임", "공개닉네임", null, java.time.Instant.now());
        users.saveAndFlush(kakao);
        for (String kind : List.of("followers", "followings")) {
            assertThat(list(actor.getId(), kind, token).rows().getFirst()).containsEntry("nickname", "공개닉네임");
        }
    }

    @Test
    void emptyAndOutOfRangePagesPreserveMetadata() {
        for (String kind : List.of("followers", "followings")) {
            var empty = list(actor.getId(), kind, token);
            assertThat(empty.status()).isEqualTo(200);
            assertThat(empty.rows()).isEmpty();
            assertThat(empty.data()).containsEntry("page", 0).containsEntry("size", 20)
                .containsEntry("totalElements", 0).containsEntry("totalPages", 0);
        }
        change("POST", target.getId(), token);
        var end = request("GET", path(actor.getId(), "followings") + "?page=10000&size=100", token);
        assertThat(end.status()).isEqualTo(200);
        assertThat(end.rows()).isEmpty();
        assertThat(end.data()).containsEntry("totalElements", 1);
    }

    @Test
    void bothListsSortByTimestampThenRelationIdAndPageCorrectly() {
        change("POST", target.getId(), token);
        change("POST", third.getId(), token);
        jdbc.update("UPDATE user_follows SET created_at = TIMESTAMP '2026-10-01 00:00:00'");
        var first = request("GET", path(actor.getId(), "followings") + "?size=1", token);
        assertThat(first.ids()).containsExactly(third.getId());
        assertThat(first.data()).containsEntry("totalElements", 2).containsEntry("totalPages", 2);
        assertThat(request("GET", path(actor.getId(), "followings") + "?size=1&page=1", token).ids()).containsExactly(target.getId());
        jdbc.update("UPDATE user_follows SET created_at = TIMESTAMP '2026-10-02 00:00:00' WHERE following_id=?", target.getId());
        assertThat(list(actor.getId(), "followings", token).ids()).containsExactly(target.getId(), third.getId());
        change("POST", actor.getId(), targetToken);
        change("POST", actor.getId(), tokens.createAccessToken(third.getId()));
        jdbc.update("UPDATE user_follows SET created_at = TIMESTAMP '2026-10-01 00:00:00'");
        assertThat(request("GET", path(actor.getId(), "followers") + "?size=1", token).ids()).containsExactly(third.getId());
        assertThat(request("GET", path(actor.getId(), "followers") + "?size=1&page=1", token).ids()).containsExactly(target.getId());
        jdbc.update("UPDATE user_follows SET created_at = TIMESTAMP '2026-10-02 00:00:00' WHERE follower_id=?", target.getId());
        assertThat(list(actor.getId(), "followers", token).ids()).containsExactly(target.getId(), third.getId());
    }

    @Test
    void removalAndReregistrationMakesRelationNewest() {
        change("POST", target.getId(), token); change("POST", third.getId(), token);
        change("DELETE", target.getId(), token); change("POST", target.getId(), token);
        assertThat(list(actor.getId(), "followings", token).ids()).containsExactly(target.getId(), third.getId());
    }

    @Test
    void everyEndpointRequiresAnAccessToken() {
        for (String[] operation : operations()) {
            for (String invalid : new String[]{null, "invalid", tokens.createRefreshToken(actor.getId())}) {
                error(request(operation[0], path(target.getId(), operation[1]), invalid), 401, "AUTH_004");
            }
        }
    }

    @Test
    void missingActorAndTargetAreRejectedOnEveryEndpoint() {
        for (String[] operation : operations()) {
            error(request(operation[0], path(Long.MAX_VALUE, operation[1]), token), 404, "USER_001");
            error(request(operation[0], path(target.getId(), operation[1]), tokens.createAccessToken(Long.MAX_VALUE)), 401, "AUTH_004");
        }
        assertThat(count()).isZero();
    }

    @Test
    void deletedActorRequestingItsOwnMissingIdAlwaysReturnsNotFound() {
        String staleToken = tokens.createAccessToken(Long.MAX_VALUE);
        for (String[] operation : operations()) {
            error(request(operation[0], path(Long.MAX_VALUE, operation[1]), staleToken), 401, "AUTH_004");
        }
        assertThat(count()).isZero();
    }

    @Test
    void selfChangesFailButSelfListsAreAllowed() {
        for (String method : List.of("POST", "DELETE")) error(change(method, actor.getId(), token), 400, "COMMON_002");
        assertThat(list(actor.getId(), "followers", token).status()).isEqualTo(200);
        assertThat(list(actor.getId(), "followings", token).status()).isEqualTo(200);
        assertThat(count()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "abc", "1.5", "9223372036854775808"})
    void invalidIdsUseBadRequest(String id) {
        for (String[] operation : operations()) error(request(operation[0], "/api/users/" + id + "/" + operation[1], token), 400, "COMMON_002");
    }

    @ParameterizedTest
    @ValueSource(strings = {"page=-1", "page=10001", "page=abc", "page=2147483648", "size=0", "size=101", "size=1.5"})
    void invalidPaginationUsesInputError(String query) {
        for (String kind : List.of("followers", "followings")) error(request("GET", path(actor.getId(), kind) + "?" + query, token), 400, "COMMON_001");
    }

    @Test
    void concurrentRepeatsAndMixedChangesAreIdempotent() throws Exception {
        var posts = concurrent(Collections.nCopies(8, new Change("POST", target.getId(), token)));
        assertThat(posts).allSatisfy(result -> {
            assertThat(result.status()).isEqualTo(200); assertThat(result.data()).containsEntry("following", true);
        });
        assertThat(count()).isEqualTo(1L);
        var deletes = concurrent(Collections.nCopies(8, new Change("DELETE", target.getId(), token)));
        assertThat(deletes).allSatisfy(result -> {
            assertThat(result.status()).isEqualTo(200); assertThat(result.data()).containsEntry("following", false);
        });
        assertThat(count()).isZero();
        change("POST", actor.getId(), targetToken);
        var mixed = new ArrayList<Change>();
        for (int i = 0; i < 8; i++) mixed.add(new Change(i % 2 == 0 ? "POST" : "DELETE", target.getId(), token));
        assertThat(concurrent(mixed)).allSatisfy(result -> assertThat(result.status()).isEqualTo(200));
        assertThat(list(target.getId(), "followings", token).ids()).containsExactly(actor.getId());
        assertThat(count()).isBetween(1L, 2L);
    }

    @Test
    void opposingConcurrentFollowsAvoidDeadlocksAndPreserveBothDirections() throws Exception {
        var changes = new ArrayList<Change>();
        for (int i = 0; i < 8; i++) changes.add(i % 2 == 0 ? new Change("POST", target.getId(), token) : new Change("POST", actor.getId(), targetToken));
        assertThat(concurrent(changes)).allSatisfy(result -> assertThat(result.status()).isEqualTo(200));
        assertThat(count()).isEqualTo(2L);
        assertThat(list(actor.getId(), "followings", token).ids()).containsExactly(target.getId());
        assertThat(list(target.getId(), "followings", token).ids()).containsExactly(actor.getId());
    }

    @Test
    void listsHaveBoundedQueryCountAtMaximumPageSize() {
        for (int i = 0; i < 101; i++) {
            var user = user();
            change("POST", user.getId(), token);
            change("POST", actor.getId(), tokens.createAccessToken(user.getId()));
        }
        var statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        for (String kind : List.of("followers", "followings")) {
            statistics.clear();
            var page = request("GET", path(actor.getId(), kind) + "?size=100", token);
            assertThat(page.rows()).hasSize(100);
            assertThat(page.data()).containsEntry("totalElements", 101).containsEntry("totalPages", 2);
            assertThat(statistics.getPrepareStatementCount()).isBetween(2L, 4L);
            assertThat(statistics.getEntityFetchCount()).isZero();
            assertThat(statistics.getCollectionFetchCount()).isZero();
            assertThat(request("GET", path(actor.getId(), kind) + "?size=100&page=1", token).rows()).hasSize(1);
        }
    }

    @Test
    void eitherParentDeletionCleansUpRelations() {
        change("POST", target.getId(), token); change("POST", actor.getId(), targetToken);
        jdbc.update("DELETE FROM users WHERE id=?", actor.getId());
        assertThat(count()).isZero();
        change("POST", third.getId(), targetToken);
        jdbc.update("DELETE FROM users WHERE id=?", third.getId());
        assertThat(count()).isZero();
    }

    @Test
    @SuppressWarnings("unchecked")
    void swaggerDocumentsAllFourAuthenticatedOperations() {
        var paths = (Map<String, Map<String, Map<String, Object>>>) request("GET", "/v3/api-docs", null).body().get("paths");
        assertThat(paths.get("/api/users/{userId}/follow")).containsOnlyKeys("post", "delete");
        for (String suffix : List.of("follow", "followers", "followings")) {
            for (var operation : paths.get("/api/users/{userId}/" + suffix).values()) {
                assertThat(operation.get("security").toString()).contains("bearerAuth");
                assertThat((Map<String, ?>) operation.get("responses")).containsKeys("200", "400", "401", "404");
            }
        }
    }

    private List<Response> concurrent(List<Change> changes) throws Exception {
        var barrier = new CyclicBarrier(changes.size());
        try (var executor = Executors.newFixedThreadPool(changes.size())) {
            var tasks = new ArrayList<Callable<Response>>();
            for (var change : changes) tasks.add(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                return change(change.method(), change.target(), change.token());
            });
            var results = new ArrayList<Response>();
            for (var future : executor.invokeAll(tasks, 30, TimeUnit.SECONDS)) results.add(future.get());
            return results;
        }
    }
    private record Change(String method, long target, String token) {}
    private String[][] operations() { return new String[][]{{"POST", "follow"}, {"DELETE", "follow"}, {"GET", "followers"}, {"GET", "followings"}}; }
    private long count() { return jdbc.queryForObject("SELECT count(*) FROM user_follows", Long.class); }
    private User user() {
        String id = UUID.randomUUID().toString();
        return users.saveAndFlush(User.builder().nickname("검증사용자").email(id + "@example.test")
            .authProvider(AuthProvider.KAKAO).providerUserId(id).build());
    }
    private String path(long id, String suffix) { return "/api/users/" + id + "/" + suffix; }
    private Response change(String method, long id, String token) { return request(method, path(id, "follow"), token); }
    private Response list(long id, String kind, String token) { return request("GET", path(id, kind), token); }
    private Response request(String method, String path, String token) { return request(method, path, token, null); }
    private Response request(String method, String path, String token, Object body) {
        var request = RestClient.create("http://127.0.0.1:" + port).method(HttpMethod.valueOf(method)).uri(path);
        if (token != null) request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        if (body != null) request.body(body);
        return request.exchange((req, res) -> new Response(res.getStatusCode().value(), res.bodyTo(Map.class)));
    }
    private void error(Response response, int status, String code) {
        assertThat(response.status()).isEqualTo(status);
        assertThat(response.body()).containsEntry("success", false).doesNotContainKey("data");
        assertThat(((Map<?, ?>) response.body().get("error")).get("code")).isEqualTo(code);
    }
    @SuppressWarnings("unchecked")
    record Response(int status, Map<String, Object> body) {
        Map<String, Object> data() { return (Map<String, Object>) body.get("data"); }
        List<Map<String, Object>> rows() { return (List<Map<String, Object>>) data().get("content"); }
        List<Long> ids() { return rows().stream().map(row -> ((Number) row.get("id")).longValue()).toList(); }
    }
}
