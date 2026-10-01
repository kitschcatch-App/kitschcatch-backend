// 실제 JWT HTTP 요청으로 공개 프로필과 선택적 필드 및 저장 경합을 검증한다.
package com.kitschcatch.backend.domain.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;

import com.kitschcatch.backend.domain.user.entity.AuthProvider;
import com.kitschcatch.backend.domain.user.entity.User;
import com.kitschcatch.backend.domain.user.repository.UserRepository;
import com.kitschcatch.backend.domain.user.service.ProfileImageStorage;
import com.kitschcatch.backend.domain.user.service.ProfileImageMetadata;
import com.kitschcatch.backend.global.security.JwtTokenProvider;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.web.client.RestClient;

abstract class PublicUserProfileHttpContract {
	@LocalServerPort int port;
	@MockitoSpyBean UserRepository users;
	@Autowired JwtTokenProvider tokens;
	@MockitoBean ProfileImageStorage images;

	@BeforeEach
	void clearUsers() {
		users.deleteAllInBatch();
	}

	@Test
	void publicProfileHasSameSafeFieldsForSelfAndOtherAndHidesUnregisteredUsers() {
		User owner = user("owner"), other = user("other");
		assertError(get(owner.getId(), "/api/users/" + owner.getId()), 404, "USER_001");
		String key = image(owner);
		assertThat(register(owner, Map.of("nickname", "collector", "username", " Collector_1 ",
			"bio", " 가 수집 ", "profileImageKey", key)).status()).isEqualTo(201);
		var own = get(owner.getId(), "/api/users/" + owner.getId());
		var publicData = own.data();
		assertThat(own.status()).isEqualTo(200);
		assertThat(publicData.keySet()).containsExactlyInAnyOrder("id", "nickname", "username", "bio", "profileImageUrl", "profileRegisteredAt");
		assertThat(publicData).containsEntry("username", "collector_1").containsEntry("bio", "가 수집")
			.containsEntry("profileImageUrl", "https://cdn.example/" + key);
		assertThat(get(other.getId(), "/api/users/" + owner.getId()).data()).isEqualTo(publicData);
		assertError(get(null, "/api/users/" + owner.getId()), 401, "AUTH_004");
		assertError(get(owner.getId(), "/api/users/" + other.getId()), 404, "USER_001");
		assertError(get(owner.getId(), "/api/users/9223372036854775807"), 404, "USER_001");
		for (String invalid : List.of("0", "-1", "abc", "9223372036854775808")) {
			assertError(get(owner.getId(), "/api/users/" + invalid), 400, "COMMON_002");
		}
	}

	@Test
	void legacyRequestsRemainValidAndPartialUpdatesPreserveOmittedFieldsAndRegistrationTime() {
		User owner = user("legacy"), other = user("legacy-other");
		var registered = register(owner, Map.of("nickname", "legacy-name"));
		assertThat(registered.status()).isEqualTo(201);
		assertThat(registered.data().get("username")).isNull();
		assertThat(registered.data().get("bio")).isNull();
		var at = registered.data().get("profileRegisteredAt");
		assertThat(patch(owner, Map.of("username", " New_Name ", "bio", "intro")).data())
			.containsEntry("username", "new_name").containsEntry("bio", "intro").containsEntry("profileRegisteredAt", at);
		var renamed = patch(owner, Map.of("nickname", "renamed"));
		assertThat(renamed.data()).containsEntry("username", "new_name").containsEntry("bio", "intro");
		assertThat(availability(owner, "NEW_NAME").data().get("available")).isEqualTo(true);
		assertThat(availability(other, "new_name").data().get("available")).isEqualTo(false);
		var clear = new HashMap<String, Object>();
		clear.put("username", null);
		clear.put("bio", null);
		assertThat(patch(owner, clear).data()).containsEntry("username", null).containsEntry("bio", null)
			.containsEntry("nickname", "renamed").containsEntry("profileRegisteredAt", at);
		assertThat(register(other, Map.of("nickname", "other", "username", "new_name")).status()).isEqualTo(201);
		assertThat(patch(owner, Map.of("bio", "   ")).data().get("bio")).isNull();
	}

	@Test
	void availabilityNormalizesAndExcludesOnlyAuthenticatedUserWithoutReservingOrCallingStorage() {
		User owner = user("available"), other = user("available-other");
		assertThat(availability(owner, " Name ").data()).containsEntry("username", "name").containsEntry("available", true);
		assertThat(register(other, Map.of("nickname", "name", "username", "NAME")).status()).isEqualTo(201);
		assertThat(availability(owner, "name").data().get("available")).isEqualTo(false);
		assertThat(get(owner.getId(), "/api/users/username-availability?username=name&userId=" + other.getId()).data().get("available"))
			.isEqualTo(false);
		assertThat(availability(other, "name").data().get("available")).isEqualTo(true);
		assertThat(availability(owner, "x".repeat(30)).status()).isEqualTo(200);
		for (String invalid : List.of("", "   ", "ab", "x".repeat(31), "아이디", "a-b", "a.b", "a b", "a\nb")) {
			assertError(availability(owner, invalid), 400, "COMMON_001");
		}
		assertError(get(owner.getId(), "/api/users/username-availability"), 400, "COMMON_002");
		assertError(get(null, "/api/users/username-availability?username=valid"), 401, "AUTH_004");
		assertError(get(9223372036854775807L, "/api/users/username-availability?username=valid"), 404, "USER_001");
		assertError(register(owner, Map.of("nickname", "distinct", "username", "Name")), 409, "USER_008");
		assertThat(users.findById(owner.getId()).orElseThrow().isProfileRegistered()).isFalse();
		verifyNoInteractions(images);
	}

	@Test
	void jsonTypesUnknownFieldsBioBoundariesAndInvalidUpdatesAreAtomic() {
		User owner = user("invalid");
		for (String body : List.of("{\"nickname\":\"name\",\"username\":42}",
			"{\"nickname\":\"name\",\"bio\":true}", "{\"nickname\":\"name\",\"email\":\"changed\"}", "[]")) {
			assertError(request(owner.getId(), HttpMethod.POST, "/api/users/me/profile", body), 400, "COMMON_002");
		}
		assertThat(register(owner, Map.of("nickname", "original", "bio", "가".repeat(160))).status()).isEqualTo(201);
		assertThat(users.findById(owner.getId()).orElseThrow().getBio()).isEqualTo("가".repeat(160));
		for (String invalid : List.of("x".repeat(161), "a\nb", "a\rb", "a\tb", "a\u0000b", "a\u2028b", "a\u2029b")) {
			assertError(patch(owner, Map.of("nickname", "failed", "bio", invalid)), 400, "COMMON_001");
			assertThat(users.findById(owner.getId()).orElseThrow().getNickname()).isEqualTo("original");
		}
		for (String body : List.of("{\"username\":[]}", "{\"bio\":{}}", "{\"id\":5}", "{}")) {
			assertError(request(owner.getId(), HttpMethod.PATCH, "/api/users/me", body), 400, "COMMON_002");
		}
		assertError(patch(owner, Map.of("username", "  ")), 400, "COMMON_001");
		assertError(patch(user("unregistered"), Map.of("username", "valid")), 409, "USER_003");
	}

	@Test
	void duplicateUpdateAndFailedImageValidationPreserveEveryProfileField() {
		User owner = user("atomic"), other = user("atomic-other");
		assertThat(register(owner, Map.of("nickname", "original", "username", "original", "bio", "old", "profileImageKey", image(owner))).status()).isEqualTo(201);
		assertThat(register(other, Map.of("nickname", "occupied", "username", "occupied")).status()).isEqualTo(201);
		var before = get(owner.getId(), "/api/users/me").data();
		assertError(patch(owner, Map.of("nickname", "changed", "username", "OCCUPIED", "bio", "new")), 409, "USER_008");
		assertThat(get(owner.getId(), "/api/users/me").data()).isEqualTo(before);
		String missingKey = "profiles/" + owner.getId() + "/missing.png";
		when(images.metadata(missingKey)).thenReturn(Optional.empty());
		assertError(patch(owner, Map.of("username", "changed", "bio", "new", "profileImageKey", missingKey)), 400, "USER_006");
		assertThat(get(owner.getId(), "/api/users/me").data()).isEqualTo(before);
	}

	@RepeatedTest(3)
	void twoRegistrationsPassingPrechecksStillCommitOnlyOneUsername() throws Exception {
		User first = user("race-first"), second = user("race-second");
		String firstImage = image(first), secondImage = image(second);
		barrierForUsername("race_name");
		List<Response> results = concurrently(
			() -> register(first, Map.of("nickname", "first", "username", "RACE_NAME", "bio", "first bio", "profileImageKey", firstImage)),
			() -> register(second, Map.of("nickname", "second", "username", " race_name ", "bio", "second bio", "profileImageKey", secondImage)));
		assertThat(results).extracting(Response::status).containsExactlyInAnyOrder(201, 409);
		assertThat(results.stream().filter(r -> r.status() == 409).findFirst().orElseThrow().error()).isEqualTo("USER_008");
		assertThat(users.findAll().stream().filter(User::isProfileRegistered)).hasSize(1);
		User loser = users.findAll().stream().filter(u -> !u.isProfileRegistered()).findFirst().orElseThrow();
		assertThat(loser.getNickname()).isEqualTo("provider-default");
		assertThat(loser.getUsername()).isNull();
		assertThat(loser.getBio()).isNull();
		assertThat(loser.getNicknameKey()).isNull();
		assertThat(loser.getProfileImageKey()).isNull();
	}

	@RepeatedTest(3)
	void twoUpdatesPassingPrechecksRollbackAllFieldsOfLoser() throws Exception {
		User first = user("patch-first"), second = user("patch-second");
		String firstImage = image(first), secondImage = image(second);
		register(first, Map.of("nickname", "first", "username", "first", "bio", "first bio"));
		register(second, Map.of("nickname", "second", "username", "second", "bio", "second bio"));
		Map<Long, Map<String, Object>> before = Map.of(first.getId(), get(first.getId(), "/api/users/me").data(),
			second.getId(), get(second.getId(), "/api/users/me").data());
		barrierForUsername("race_name");
		List<Response> results = concurrently(
			() -> patch(first, Map.of("nickname", "first changed", "username", "race_name", "bio", "new", "profileImageKey", firstImage)),
			() -> patch(second, Map.of("nickname", "second changed", "username", "RACE_NAME", "bio", "new", "profileImageKey", secondImage)));
		assertThat(results).extracting(Response::status).containsExactlyInAnyOrder(200, 409);
		assertThat(results.stream().filter(r -> r.status() == 409).findFirst().orElseThrow().error()).isEqualTo("USER_008");
		assertThat(users.findAll().stream().filter(u -> "race_name".equals(u.getUsername()))).hasSize(1);
		User loser = users.findAll().stream().filter(u -> !"race_name".equals(u.getUsername())).findFirst().orElseThrow();
		assertThat(get(loser.getId(), "/api/users/me").data()).isEqualTo(before.get(loser.getId()));
	}

	@Test
	void simultaneousPartialUpdatesOnSameUserPreserveAllChanges() throws Exception {
		User owner = user("partial");
		var registered = register(owner, Map.of("nickname", "original"));
		List<Response> results = concurrently(
			() -> patch(owner, Map.of("username", "new_name")),
			() -> patch(owner, Map.of("bio", "new bio")),
			() -> patch(owner, Map.of("nickname", "new nickname", "profileImageKey", image(owner))));
		assertThat(results).extracting(Response::status).containsOnly(200);
		assertThat(get(owner.getId(), "/api/users/me").data()).containsEntry("username", "new_name")
			.containsEntry("bio", "new bio").containsEntry("nickname", "new nickname")
			.containsEntry("profileImageKey", "profiles/" + owner.getId() + "/image.png")
			.containsEntry("profileRegisteredAt", registered.data().get("profileRegisteredAt"));
	}

	@Test
	void openApiExposesSeparatePublicSchemaAndOptionalFields() {
		Map<String, Object> document = get(null, "/v3/api-docs").body();
		var schemas = (Map<?, ?>) ((Map<?, ?>) document.get("components")).get("schemas");
		var publicProperties = (Map<?, ?>) ((Map<?, ?>) schemas.get("PublicUserProfileResponse")).get("properties");
		assertThat(publicProperties.keySet().stream().map(Object::toString).toList())
			.containsExactlyInAnyOrder("id", "nickname", "username", "bio", "profileImageUrl", "profileRegisteredAt");
		var paths = (Map<?, ?>) document.get("paths");
		assertThat(paths.containsKey("/api/users/{userId}")).isTrue();
		assertThat(paths.containsKey("/api/users/username-availability")).isTrue();
		var requestSchema = (Map<?, ?>) schemas.get("RegisterUserProfileRequest");
		assertThat((List<?>) requestSchema.get("required")).hasSize(1);
	}

	private void barrierForUsername(String username) {
		CyclicBarrier checked = new CyclicBarrier(2);
		var delegate = mockingDetails(users).getMockCreationSettings().getDefaultAnswer();
		doAnswer(invocation -> {
			boolean occupied = (boolean) delegate.answer(invocation);
			assertThat(occupied).isFalse();
			checked.await(10, TimeUnit.SECONDS);
			return occupied;
		}).when(users).existsByUsernameAndIdNot(eq(username), anyLong());
	}

	private User user(String providerId) {
		return users.saveAndFlush(User.builder().nickname("provider-default").email(providerId + "@example.com")
			.authProvider(AuthProvider.KAKAO).providerUserId(providerId).build());
	}

	private String image(User user) {
		String key = "profiles/" + user.getId() + "/image.png";
		when(images.metadata(key)).thenReturn(Optional.of(new ProfileImageMetadata("image/png", 1024)));
		when(images.imageUrl(key)).thenReturn("https://cdn.example/" + key);
		return key;
	}

	private Response register(User user, Object body) { return request(user.getId(), HttpMethod.POST, "/api/users/me/profile", body); }
	private Response patch(User user, Object body) { return request(user.getId(), HttpMethod.PATCH, "/api/users/me", body); }
	private Response get(Long userId, String path) { return request(userId, HttpMethod.GET, path, null); }
	private Response availability(User user, String username) {
		return get(user.getId(), "/api/users/username-availability?username=" + URLEncoder.encode(username, StandardCharsets.UTF_8));
	}

	private Response request(Long userId, HttpMethod method, String path, Object body) {
		var request = RestClient.create().method(method).uri(URI.create("http://127.0.0.1:" + port + path));
		if (userId != null) request.header(HttpHeaders.AUTHORIZATION, "Bearer " + tokens.createAccessToken(userId));
		if (body != null) request.contentType(MediaType.APPLICATION_JSON).body(body);
		return request.exchange((req, res) -> new Response(res.getStatusCode().value(), res.bodyTo(Map.class)));
	}

	@SafeVarargs
	private final List<Response> concurrently(Callable<Response>... calls) throws Exception {
		CyclicBarrier start = new CyclicBarrier(calls.length);
		try (var executor = Executors.newFixedThreadPool(calls.length)) {
			var futures = Arrays.stream(calls).map(call -> executor.submit(() -> {
				start.await(10, TimeUnit.SECONDS);
				return call.call();
			})).toList();
			var results = new java.util.ArrayList<Response>();
			for (var future : futures) results.add(future.get(20, TimeUnit.SECONDS));
			return results;
		}
	}

	private void assertError(Response response, int status, String code) {
		assertThat(response.status()).isEqualTo(status);
		assertThat(response.error()).isEqualTo(code);
	}

	record Response(int status, Map<String, Object> body) {
		@SuppressWarnings("unchecked")
		Map<String, Object> data() { return (Map<String, Object>) body.get("data"); }
		String error() { return (String) ((Map<?, ?>) body.get("error")).get("code"); }
	}
}
