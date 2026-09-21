// 수동 SQL을 적용한 PostgreSQL에서 실제 JWT HTTP 요청의 프로필 경합과 원자성을 검증한다.
package com.kitschcatch.backend.domain.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.when;

import com.kitschcatch.backend.domain.user.entity.AuthProvider;
import com.kitschcatch.backend.domain.user.entity.User;
import com.kitschcatch.backend.domain.user.repository.UserRepository;
import com.kitschcatch.backend.domain.user.service.ProfileImageStorage;
import com.kitschcatch.backend.global.security.JwtTokenProvider;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.web.client.RestClient;
import software.amazon.awssdk.services.s3.model.S3Exception;

@EnabledIfEnvironmentVariable(named = "ISSUE31_TEST_DB_URL", matches = "jdbc:postgresql:.*")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
	"spring.datasource.driver-class-name=org.postgresql.Driver",
	"spring.jpa.hibernate.ddl-auto=create",
	"kakao.oauth.native-app-key=test-native-app-key",
	"app.orders.expiration-enabled=false",
	"app.payments.recovery.enabled=false"
})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DirtiesContext
class UserProfilePostgresHttpTest {

	private static PostgresProfileTestDatabase database;

	@DynamicPropertySource
	static void databaseProperties(DynamicPropertyRegistry registry) throws Exception {
		database = new PostgresProfileTestDatabase();
		registry.add("spring.datasource.url", () -> database.url);
		registry.add("spring.datasource.username", () -> database.username);
		registry.add("spring.datasource.password", () -> database.password);
		registry.add("spring.datasource.hikari.schema", () -> database.schema);
		registry.add("spring.jpa.properties.hibernate.default_schema", () -> database.schema);
	}

	@LocalServerPort
	private int port;
	@MockitoSpyBean
	private UserRepository userRepository;
	@Autowired
	private JwtTokenProvider jwtTokenProvider;
	@MockitoBean
	private ProfileImageStorage profileImageStorage;

	@BeforeAll
	void applyManualMigrationToLegacyUserTable() throws Exception {
		try (var connection = database.connect(); var statement = connection.createStatement()) {
			// Hibernate가 만든 프로필 컬럼과 유일 제약을 제거해 실제 수동 SQL로만 다시 구성한다.
			statement.execute("ALTER TABLE users DROP COLUMN nickname_key, DROP COLUMN profile_image_key, DROP COLUMN profile_registered_at");
			PostgresProfileTestDatabase.migrate(connection);
		}
	}

	@AfterAll
	void dropIsolatedSchema() throws Exception {
		database.close();
	}

	@BeforeEach
	void clearUsers() {
		userRepository.deleteAllInBatch();
	}

	@RepeatedTest(5)
	void concurrentNicknameRegistrationCommitsOnlyOneUserAndRollsBackLoser() throws Exception {
		User first = saveUser("first");
		User second = saveUser("second");
		CyclicBarrier bothCheckedAvailability = new CyclicBarrier(2);
		var repositoryDelegate = mockingDetails(userRepository).getMockCreationSettings().getDefaultAnswer();
		doAnswer(invocation -> {
			boolean occupied = (boolean) repositoryDelegate.answer(invocation);
			assertThat(occupied).isFalse();
			bothCheckedAvailability.await(10, TimeUnit.SECONDS);
			return occupied;
		}).when(userRepository).existsByNicknameKeyAndIdNot(eq("공동닉네임"), anyLong());

		List<Response> responses = concurrently(
			() -> register(first.getId(), Map.of("nickname", " 공동닉네임 ")),
			() -> register(second.getId(), Map.of("nickname", "공동닉네임")));

		assertThat(responses).extracting(Response::status).containsExactlyInAnyOrder(201, 409);
		assertThat(responses.stream().filter(response -> response.status() == 409).findFirst().orElseThrow().errorCode())
			.isEqualTo("USER_004");
		List<User> users = userRepository.findAll();
		assertThat(users.stream().filter(User::isProfileRegistered)).hasSize(1);
		User winner = users.stream().filter(User::isProfileRegistered).findFirst().orElseThrow();
		assertThat(winner.getNicknameKey()).isEqualTo("공동닉네임");
		User loser = users.stream().filter(user -> !user.isProfileRegistered()).findFirst().orElseThrow();
		assertThat(loser.getNickname()).isEqualTo("카카오기본닉네임");
		assertThat(loser.getNicknameKey()).isNull();
		assertThat(loser.getProfileImageKey()).isNull();
	}

	@Test
	void concurrentRegistrationForSameUserSucceedsOnce() throws Exception {
		User user = saveUser("same-user");
		List<Response> responses = concurrently(
			() -> register(user.getId(), Map.of("nickname", "first-name")),
			() -> register(user.getId(), Map.of("nickname", "second-name")));

		assertThat(responses).extracting(Response::status).containsExactlyInAnyOrder(201, 409);
		assertThat(responses.stream().filter(response -> response.status() == 409).findFirst().orElseThrow().errorCode())
			.isEqualTo("USER_002");
		User saved = userRepository.findById(user.getId()).orElseThrow();
		String winnerNickname = (String) responses.stream().filter(response -> response.status() == 201)
			.findFirst().orElseThrow().data().get("nickname");
		assertThat(saved.getNickname()).isEqualTo(winnerNickname);
		assertThat(saved.getNicknameKey()).isEqualTo(winnerNickname);
		assertThat(saved.getProfileRegisteredAt()).isNotNull();
	}

	@Test
	void concurrentPartialUpdatesPreserveBothChangesAndRegistrationTime() throws Exception {
		User user = saveUser("patch-user");
		assertThat(register(user.getId(), Map.of("nickname", "original")).status()).isEqualTo(201);
		var registeredAt = userRepository.findById(user.getId()).orElseThrow().getProfileRegisteredAt();
		String imageKey = "profiles/" + user.getId() + "/new.png";
		when(profileImageStorage.isOwnedProfileImageKey(user.getId(), imageKey)).thenReturn(true);
		when(profileImageStorage.exists(imageKey)).thenReturn(true);
		when(profileImageStorage.imageUrl(imageKey)).thenReturn("https://cdn.example/" + imageKey);

		List<Response> responses = concurrently(
			() -> patch(user.getId(), Map.of("nickname", "renamed")),
			() -> patch(user.getId(), Map.of("profileImageKey", imageKey)));

		assertThat(responses).extracting(Response::status).containsExactly(200, 200);
		User saved = userRepository.findById(user.getId()).orElseThrow();
		assertThat(saved.getNickname()).isEqualTo("renamed");
		assertThat(saved.getNicknameKey()).isEqualTo("renamed");
		assertThat(saved.getProfileImageKey()).isEqualTo(imageKey);
		assertThat(saved.getProfileRegisteredAt()).isEqualTo(registeredAt);
	}

	@Test
	void s3PermissionFailureDoesNotRegisterOrPartiallyUpdateUser() {
		User user = saveUser("s3-error");
		String imageKey = "profiles/" + user.getId() + "/denied.png";
		when(profileImageStorage.isOwnedProfileImageKey(user.getId(), imageKey)).thenReturn(true);
		when(profileImageStorage.exists(imageKey)).thenThrow(S3Exception.builder().statusCode(403).build());

		assertThat(register(user.getId(), Map.of("nickname", "failed", "profileImageKey", imageKey)).status()).isEqualTo(500);
		User unregistered = userRepository.findById(user.getId()).orElseThrow();
		assertThat(unregistered.getNickname()).isEqualTo("카카오기본닉네임");
		assertThat(unregistered.getNicknameKey()).isNull();
		assertThat(unregistered.getProfileRegisteredAt()).isNull();
		assertThat(register(user.getId(), Map.of("nickname", "original")).status()).isEqualTo(201);

		assertThat(patch(user.getId(), Map.of("nickname", "failed", "profileImageKey", imageKey)).status()).isEqualTo(500);
		User unchanged = userRepository.findById(user.getId()).orElseThrow();
		assertThat(unchanged.getNickname()).isEqualTo("original");
		assertThat(unchanged.getNicknameKey()).isEqualTo("original");
		assertThat(unchanged.getProfileImageKey()).isNull();
	}

	@Test
	void slowS3ValidationDoesNotHoldUserRowLock() throws Exception {
		User user = saveUser("slow-s3");
		String imageKey = "profiles/" + user.getId() + "/slow.png";
		CountDownLatch enteredS3 = new CountDownLatch(1);
		CountDownLatch releaseS3 = new CountDownLatch(1);
		when(profileImageStorage.isOwnedProfileImageKey(user.getId(), imageKey)).thenReturn(true);
		when(profileImageStorage.exists(imageKey)).thenAnswer(invocation -> {
			enteredS3.countDown();
			assertThat(releaseS3.await(10, TimeUnit.SECONDS)).isTrue();
			return true;
		});
		var executor = Executors.newSingleThreadExecutor();
		try {
			var registering = executor.submit(() -> register(user.getId(), Map.of("nickname", "slow", "profileImageKey", imageKey)));
			try {
				assertThat(enteredS3.await(10, TimeUnit.SECONDS)).isTrue();
				try (var connection = database.connect(); var statement = connection.prepareStatement(
					"SELECT id FROM users WHERE id = ? FOR UPDATE NOWAIT")) {
					statement.setLong(1, user.getId());
					try (var result = statement.executeQuery()) {
						assertThat(result.next()).isTrue();
					}
				}
			} finally {
				releaseS3.countDown();
			}
			assertThat(registering.get(15, TimeUnit.SECONDS).status()).isEqualTo(201);
		} finally {
			releaseS3.countDown();
			executor.shutdownNow();
		}
	}

	@Test
	void unrelatedConstraintFailureIsNotReportedAsDuplicateNickname() throws Exception {
		User user = saveUser("other-constraint");
		try (var connection = database.connect(); var statement = connection.createStatement()) {
			statement.execute("ALTER TABLE users ADD CONSTRAINT ck_test_nickname CHECK (nickname <> 'blocked')");
			try {
				Response response = register(user.getId(), Map.of("nickname", "blocked"));
				assertThat(response.status()).isEqualTo(500);
				assertThat(response.errorCode()).isEqualTo("COMMON_999");
				User saved = userRepository.findById(user.getId()).orElseThrow();
				assertThat(saved.getNickname()).isEqualTo("카카오기본닉네임");
				assertThat(saved.getNicknameKey()).isNull();
				assertThat(saved.getProfileRegisteredAt()).isNull();
			} finally {
				statement.execute("ALTER TABLE users DROP CONSTRAINT ck_test_nickname");
			}
		}
	}

	private User saveUser(String providerId) {
		return userRepository.saveAndFlush(User.builder().nickname("카카오기본닉네임")
			.email(providerId + "@example.com").authProvider(AuthProvider.KAKAO).providerUserId(providerId).build());
	}

	private Response register(Long userId, Map<String, Object> body) {
		return request(userId, HttpMethod.POST, "/api/users/me/profile", body);
	}

	private Response patch(Long userId, Map<String, Object> body) {
		return request(userId, HttpMethod.PATCH, "/api/users/me", body);
	}

	private Response request(Long userId, HttpMethod method, String path, Map<String, Object> body) {
		return RestClient.create("http://127.0.0.1:" + port).method(method).uri(path)
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + jwtTokenProvider.createAccessToken(userId))
			.contentType(MediaType.APPLICATION_JSON).body(body)
			.exchange((request, response) -> new Response(response.getStatusCode().value(), response.bodyTo(Map.class)));
	}

	private List<Response> concurrently(Callable<Response> first, Callable<Response> second) throws Exception {
		var executor = Executors.newFixedThreadPool(2);
		try {
			CyclicBarrier start = new CyclicBarrier(2);
			var firstFuture = executor.submit(() -> { start.await(10, TimeUnit.SECONDS); return first.call(); });
			var secondFuture = executor.submit(() -> { start.await(10, TimeUnit.SECONDS); return second.call(); });
			return List.of(firstFuture.get(30, TimeUnit.SECONDS), secondFuture.get(30, TimeUnit.SECONDS));
		} finally {
			executor.shutdownNow();
		}
	}

	private record Response(int status, Map<?, ?> body) {
		String errorCode() {
			return (String) ((Map<?, ?>) body.get("error")).get("code");
		}
		Map<?, ?> data() {
			return (Map<?, ?>) body.get("data");
		}
	}
}
