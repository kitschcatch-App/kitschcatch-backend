// 실제 HTTP 서버에서 매장 조회 계약을 H2와 PostgreSQL에 공통 검증한다.
package com.kitschcatch.backend.domain.store;

import static org.assertj.core.api.Assertions.assertThat;

import com.kitschcatch.backend.domain.store.entity.Store;
import com.kitschcatch.backend.domain.store.entity.StoreBusinessHours;
import com.kitschcatch.backend.domain.store.repository.StoreRepository;
import com.kitschcatch.backend.global.security.JwtTokenProvider;
import com.kitschcatch.backend.domain.user.repository.UserRepository;
import com.kitschcatch.backend.domain.user.entity.User;
import com.kitschcatch.backend.domain.user.entity.AuthProvider;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;

abstract class StoreHttpContract {
    @LocalServerPort int port;
    @Autowired StoreRepository stores;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtTokenProvider tokens;
    @Autowired UserRepository users;
    String token;

    @BeforeEach
    void resetData() {
        jdbc.update("DELETE FROM store_business_hours");
        jdbc.update("DELETE FROM stores");
        users.deleteAllInBatch();
        var user=users.saveAndFlush(User.builder().nickname("매장 조회자").email("store-reader@example.test")
            .authProvider(AuthProvider.KAKAO).providerUserId("store-reader").build());
        token = tokens.createAccessToken(user.getId());
    }

    @Test
    void emptyListsAreSuccessful() {
        var all = get("/api/stores");
        assertThat(all.status()).isEqualTo(200);
        assertThat(all.body()).containsEntry("success", true).doesNotContainKey("error");
        assertThat(all.rows("content")).isEmpty();
        assertThat(all.data()).containsEntry("page", 0).containsEntry("size", 20)
            .containsEntry("totalElements", 0).containsEntry("totalPages", 0);
        var nearby = get("/api/stores/nearby?latitude=37.5665&longitude=126.978");
        assertThat(nearby.status()).isEqualTo(200);
        assertThat(nearby.rows("stores")).isEmpty();
        assertThat(nearby.data()).containsEntry("hasNext", false);
    }

    @Test
    void listFiltersRegionAndPaginatesInIdOrder() {
        var first = store("첫 매장", "서울", 37.5665, 126.978);
        store("제주 매장", "제주", 33.5, 126.5);
        var last = store("다음 매장", "서울", 37.57, 126.98);
        var all = get("/api/stores?size=2");
        assertThat(all.status()).isEqualTo(200);
        assertThat(all.rows("content")).hasSize(2);
        assertThat(all.rows("content").getFirst()).containsEntry("latitude", 37.5665)
            .containsEntry("longitude", 126.978).containsEntry("phone", "02-0000-0000");
        assertThat(all.data()).containsEntry("totalElements", 3).containsEntry("totalPages", 2);
        var filtered = get("/api/stores?region=서울&size=1&page=1");
        assertThat(filtered.status()).isEqualTo(200);
        assertThat(filtered.ids("content")).containsExactly(last.getId());
        assertThat(filtered.data()).containsEntry("page", 1).containsEntry("totalElements", 2);
        assertThat(get("/api/stores?region=서울&size=1").ids("content")).containsExactly(first.getId());
        assertThat(get("/api/stores?page=10000").rows("content")).isEmpty();
    }

    @Test
    void detailOrdersWeekdaysAndPreservesClosedAndOvernightHours() {
        var store = stores.saveAndFlush(Store.builder().name("영업시간 검증").region("서울").address("테스트 주소")
            .latitude(37.5).longitude(127).phone(null).businessHours(List.of(
                new StoreBusinessHours(DayOfWeek.SUNDAY, null, null, true),
                new StoreBusinessHours(DayOfWeek.FRIDAY, LocalTime.of(20, 0), LocalTime.of(2, 0), false),
                new StoreBusinessHours(DayOfWeek.MONDAY, LocalTime.of(11, 0), LocalTime.of(20, 0), false)
            )).build());
        var response = get("/api/stores/" + store.getId());
        assertThat(response.status()).isEqualTo(200);
        assertThat(response.data()).containsEntry("phone", null).containsEntry("latitude", 37.5);
        var hours = response.rows("businessHours");
        assertThat(hours).extracting(row -> row.get("dayOfWeek")).containsExactly("MONDAY", "FRIDAY", "SUNDAY");
        assertThat(hours.getFirst()).containsEntry("openTime", "11:00").containsEntry("closeTime", "20:00");
        assertThat(hours.get(1)).containsEntry("closeTime", "02:00");
        assertThat(hours.get(2)).containsEntry("closed", true).containsEntry("openTime", null).containsEntry("closeTime", null);
    }

    @Test
    void detailDistinguishesMissingHoursFromMissingStore() {
        var store = store("시간 미등록", "서울", 0, 0);
        assertThat(get("/api/stores/" + store.getId()).rows("businessHours")).isEmpty();
        assertError(get("/api/stores/9223372036854775807"), 404, "STORE_001");
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/stores", "/api/stores/nearby?latitude=0&longitude=0", "/api/stores/1"})
    void allEndpointsRequireAccessToken(String path) {
        assertError(request(path, null), 401, "AUTH_004");
        assertError(request(path, "invalid"), 401, "AUTH_004");
        assertError(request(path, tokens.createRefreshToken(1L)), 401, "AUTH_004");
    }

    @ParameterizedTest
    @ValueSource(strings = {"page=-1", "page=10001", "page=no", "page=2147483648", "size=0", "size=101", "size=1.5"})
    void invalidPagesHaveConsistentErrorCodes(String query) {
        assertError(get("/api/stores?" + query), 400, "COMMON_001");
        assertError(get("/api/stores/nearby?latitude=0&longitude=0&" + query), 400, "COMMON_001");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "서울특별시", "unknown", "%"})
    void invalidRegionsAreRejected(String region) {
        assertError(get("/api/stores?region=" + region), 400, "COMMON_001");
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "abc", "1.5", "9223372036854775808"})
    void malformedStoreIdsAreBadRequests(String id) {
        assertError(get("/api/stores/" + id), 400, "COMMON_002");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "longitude=0", "latitude=0", "latitude=&longitude=0", "latitude=no&longitude=0",
        "latitude=NaN&longitude=0", "latitude=Infinity&longitude=0", "latitude=-Infinity&longitude=0",
        "latitude=90.001&longitude=0", "latitude=-90.001&longitude=0",
        "latitude=0&longitude=NaN", "latitude=0&longitude=Infinity", "latitude=0&longitude=-Infinity",
        "latitude=0&longitude=180.001", "latitude=0&longitude=-180.001",
        "latitude=0&longitude=0&radiusKm=0", "latitude=0&longitude=0&radiusKm=2",
        "latitude=0&longitude=0&radiusKm=6", "latitude=0&longitude=0&radiusKm=1.5",
        "latitude=0&longitude=0&radiusKm=word"
    })
    void invalidCoordinatesAndRadiiAreStoreErrors(String query) {
        assertError(get("/api/stores/nearby?" + query), 400, "STORE_002");
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 3, 5})
    void radiusIncludesBoundaryAndExcludesOutsideBeforeRounding(int radiusKm) {
        double boundary = northOfEquator(radiusKm * 1000.0);
        var edge = store("경계", "서울", boundary, 0);
        store("경계 밖", "서울", northOfEquator(radiusKm * 1000.0 + 0.2), 0);
        var origin = store("원점", "서울", 0, 0);
        var duplicate = store("같은 좌표", "서울", 0, 0);
        var response = get("/api/stores/nearby?latitude=0&longitude=0&radiusKm=" + radiusKm + "&size=2");
        assertThat(response.status()).isEqualTo(200);
        assertThat(response.ids("stores")).containsExactly(origin.getId(), duplicate.getId());
        assertThat(response.data()).containsEntry("hasNext", true);
        var next = get("/api/stores/nearby?latitude=0&longitude=0&radiusKm=" + radiusKm + "&size=2&page=1");
        assertThat(next.ids("stores")).containsExactly(edge.getId());
        assertThat(next.rows("stores").getFirst()).containsEntry("distanceMeters", radiusKm * 1000);
        assertThat(next.data()).containsEntry("hasNext", false);
    }

    @Test
    void roundedDistancesDoNotChangeSortOrder() {
        var farther = store("더 먼 매장", "서울", northOfEquator(999.8), 0);
        var closer = store("더 가까운 매장", "서울", northOfEquator(999.6), 0);
        var result = get("/api/stores/nearby?latitude=0&longitude=0");
        assertThat(result.status()).isEqualTo(200);
        assertThat(result.ids("stores")).containsExactly(closer.getId(), farther.getId());
        assertThat(result.rows("stores")).allSatisfy(row -> assertThat(row).containsEntry("distanceMeters", 1000));
    }

    @Test
    void supportsDateLineAndBothPoles() {
        var east = store("날짜 변경선", "서울", 0, -179.999);
        var north = store("북극 인근", "서울", 89.999, -170);
        var south = store("남극 인근", "서울", -89.999, 170);
        assertThat(get("/api/stores/nearby?latitude=0&longitude=180").ids("stores")).containsExactly(east.getId());
        assertThat(get("/api/stores/nearby?latitude=0&longitude=-180").ids("stores")).containsExactly(east.getId());
        assertThat(get("/api/stores/nearby?latitude=90&longitude=10").ids("stores")).containsExactly(north.getId());
        assertThat(get("/api/stores/nearby?latitude=-90&longitude=-10").ids("stores")).containsExactly(south.getId());
    }

    @Test
    void maximumPageSizeStillReportsTheNextPage() {
        for (int i = 0; i < 101; i++) store("매장" + i, "서울", 0, 0);
        var result = get("/api/stores/nearby?latitude=0&longitude=0&size=100");
        assertThat(result.status()).isEqualTo(200);
        assertThat(result.rows("stores")).hasSize(100);
        assertThat(result.data()).containsEntry("hasNext", true);
        assertThat(get("/api/stores/nearby?latitude=0&longitude=0&size=100&page=1").rows("stores")).hasSize(1);
    }

    @Test
    @SuppressWarnings("unchecked")
    void swaggerExposesThreeAuthenticatedReadEndpoints() {
        var response = request("/v3/api-docs", null);
        assertThat(response.status()).isEqualTo(200);
        var paths = (Map<String, Map<String, Map<String, Object>>>) response.body().get("paths");
        for (String path : List.of("/api/stores", "/api/stores/nearby", "/api/stores/{storeId}")) {
            assertThat(paths.get(path)).containsOnlyKeys("get");
            assertThat(paths.get(path).get("get").get("security").toString()).contains("bearerAuth");
        }
        assertThat(paths.get("/api/stores/nearby").get("get").get("parameters").toString())
            .contains("latitude", "longitude", "radiusKm", "page", "size");
    }

    Store store(String name, String region, double latitude, double longitude) {
        return stores.saveAndFlush(Store.builder().name(name).region(region).address("검증용 가상 주소")
            .latitude(latitude).longitude(longitude).phone("02-0000-0000").build());
    }

    private double northOfEquator(double meters) {
        return Math.toDegrees(meters / 6371008.8);
    }

    Response get(String path) { return request(path, token); }

    Response request(String path, String accessToken) {
        var request = RestClient.create("http://127.0.0.1:" + port).get().uri(path);
        if (accessToken != null) request.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken);
        return request.exchange((req, res) -> new Response(res.getStatusCode().value(), res.bodyTo(Map.class)));
    }

    void assertError(Response response, int status, String code) {
        assertThat(response.status()).isEqualTo(status);
        assertThat(response.body()).containsEntry("success", false).doesNotContainKey("data");
        assertThat(((Map<?, ?>) response.body().get("error")).get("code")).isEqualTo(code);
    }

    @SuppressWarnings("unchecked")
    record Response(int status, Map<String, Object> body) {
        Map<String, Object> data() { return (Map<String, Object>) body.get("data"); }
        List<Map<String, Object>> rows(String name) { return (List<Map<String, Object>>) data().get(name); }
        List<Long> ids(String name) { return rows(name).stream().map(row -> ((Number) row.get("id")).longValue()).toList(); }
    }
}
