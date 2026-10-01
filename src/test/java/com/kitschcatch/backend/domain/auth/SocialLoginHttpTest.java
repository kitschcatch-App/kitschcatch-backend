// 로컬 제공자 HTTP·JWKS와 실제 서비스 JWT로 소셜 로그인 및 동시성을 검증한다.
package com.kitschcatch.backend.domain.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;

import com.kitschcatch.backend.domain.auth.client.SocialIdentity;
import com.kitschcatch.backend.domain.auth.repository.LoginNonceRepository;
import com.kitschcatch.backend.domain.auth.repository.RefreshTokenRepository;
import com.kitschcatch.backend.domain.auth.service.SocialUserService;
import com.kitschcatch.backend.domain.user.entity.AuthProvider;
import com.kitschcatch.backend.domain.user.entity.User;
import com.kitschcatch.backend.domain.user.repository.UserRepository;
import com.kitschcatch.backend.global.security.JwtTokenProvider;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.web.client.RestClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
	"spring.jpa.hibernate.ddl-auto=create-drop", "kakao.oauth.native-app-key=kakao-app",
	"app.orders.expiration-enabled=false", "app.payments.recovery.enabled=false",
	"social.oauth.apple.client-id=apple-app", "social.oauth.naver.client-id=naver-app",
	"social.oauth.naver.client-secret=local-secret", "social.oauth.naver.redirect-uri=https://app.example/callback"
})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DirtiesContext
class SocialLoginHttpTest {
	private static final RSAKey KEY;
	private static final HttpServer PROVIDER;
	private static volatile String jwksBody;
	private static volatile String tokenResponse = "";
	private static volatile String profileResponse = "";
	private static volatile int tokenStatus = 200;
	private static volatile int profileStatus = 200;
	private static volatile int jwksStatus = 200;
	private static volatile String exchangeForm;
	private static volatile String profileAuthorization;
	private static final AtomicInteger EXCHANGES = new AtomicInteger();
	static {
		try {
			KEY = new RSAKeyGenerator(2048).keyID("local-key").generate();
			jwksBody = new JWKSet(KEY.toPublicJWK()).toString();
			PROVIDER = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
			PROVIDER.createContext("/jwks", exchange -> {
				byte[] body = jwksBody.getBytes(StandardCharsets.UTF_8);
				exchange.getResponseHeaders().set("Content-Type", "application/json");
				exchange.sendResponseHeaders(jwksStatus, body.length);
				try (var out = exchange.getResponseBody()) { out.write(body); }
			});
			PROVIDER.createContext("/token", exchange -> {
				EXCHANGES.incrementAndGet();
				exchangeForm = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
				byte[] body = tokenResponse.getBytes(StandardCharsets.UTF_8);
				exchange.getResponseHeaders().set("Content-Type", "application/json");
				exchange.sendResponseHeaders(tokenStatus, body.length);
				try (var out = exchange.getResponseBody()) { out.write(body); }
			});
			PROVIDER.createContext("/me", exchange -> {
				profileAuthorization = exchange.getRequestHeaders().getFirst("Authorization");
				byte[] body = profileResponse.getBytes(StandardCharsets.UTF_8);
				exchange.getResponseHeaders().set("Content-Type", "application/json");
				exchange.sendResponseHeaders(profileStatus, body.length);
				try (var out = exchange.getResponseBody()) { out.write(body); }
			});
			PROVIDER.start();
		} catch (Exception exception) { throw new ExceptionInInitializerError(exception); }
	}
	@DynamicPropertySource
	static void properties(DynamicPropertyRegistry registry) {
		String root = "http://127.0.0.1:" + PROVIDER.getAddress().getPort();
		registry.add("social.oauth.apple.jwk-set-uri", () -> root + "/jwks");
		registry.add("social.oauth.naver.token-uri", () -> root + "/token");
		registry.add("social.oauth.naver.user-info-uri", () -> root + "/me");
		registry.add("kakao.oauth.jwk-set-uri", () -> root + "/jwks");
		registry.add("spring.datasource.url", () -> dbUrl());
		registry.add("spring.datasource.driver-class-name", () -> postgres() ? "org.postgresql.Driver" : "org.h2.Driver");
		registry.add("spring.datasource.username", () -> postgres() ? "issue53" : "sa");
		registry.add("spring.datasource.password", () -> "");
	}
	private static boolean postgres() { return System.getenv("ISSUE53_TEST_DB_URL") != null; }
	private static String dbUrl() {
		return postgres() ? System.getenv("ISSUE53_TEST_DB_URL") : "jdbc:h2:mem:issue53-social;MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
	}
	@LocalServerPort private int port;
	@Autowired private UserRepository users;
	@Autowired private RefreshTokenRepository refreshTokens;
	@Autowired private LoginNonceRepository nonces;
	@Autowired private JwtTokenProvider jwt;
	@MockitoSpyBean private SocialUserService socialUsers;

	@BeforeAll
	void legacyMigrationWhenPostgres() throws Exception {
		if (!postgres()) return;
		try (var connection = DriverManager.getConnection(dbUrl(), "issue53", ""); var sql = connection.createStatement()) {
			sql.execute("ALTER TABLE users ALTER COLUMN email SET NOT NULL");
			sql.execute("ALTER TABLE users ADD CONSTRAINT legacy_unique_email UNIQUE (email)");
			sql.execute("ALTER TABLE users ADD CONSTRAINT legacy_provider CHECK (auth_provider IN ('KAKAO'))");
			sql.execute("ALTER TABLE users ADD CONSTRAINT issue53_unrelated_check CHECK (char_length(nickname) > 0)");
			sql.execute("INSERT INTO users (nickname, email, auth_provider, provider_user_id, created_at) VALUES ('existing', 'existing@example.com', 'KAKAO', 'legacy', CURRENT_TIMESTAMP)");
			String migration = Files.readString(Path.of("src/main/resources/db/manual/053_social_login.sql"));
			sql.execute(migration);
			sql.execute(migration);
			try (var result = sql.executeQuery("SELECT nickname, email, auth_provider, provider_user_id FROM users")) {
				assertThat(result.next()).isTrue(); assertThat(result.getString(1)).isEqualTo("existing");
				assertThat(result.getString(2)).isEqualTo("existing@example.com");
				assertThat(result.getString(3)).isEqualTo("KAKAO"); assertThat(result.getString(4)).isEqualTo("legacy");
			}
			try (var result = sql.executeQuery("SELECT count(*) FROM pg_constraint WHERE conrelid = 'users'::regclass AND conname IN ('issue53_unrelated_check', 'uk_users_auth_provider_provider_user_id')")) {
				result.next(); assertThat(result.getInt(1)).isEqualTo(2);
			}
		}
	}
	@AfterAll void stopProvider() { PROVIDER.stop(0); }
	@BeforeEach
	void reset() {
		refreshTokens.deleteAllInBatch(); nonces.deleteAllInBatch(); users.deleteAllInBatch();
		tokenStatus = 200; profileStatus = 200; jwksStatus = 200; EXCHANGES.set(0);
		jwksBody = new JWKSet(KEY.toPublicJWK()).toString();
		tokenResponse = "{\"access_token\":\"provider-token\",\"token_type\":\"bearer\",\"expires_in\":\"3600\"}";
		profileResponse = "{\"resultcode\":\"00\",\"response\":{\"id\":\"naver-user\",\"email\":\"same@example.com\"}}";
	}
	@Test
	void distinctProvidersAndMissingEmailsNeverLinkAccountsAndTokensRotate() throws Exception {
		Response naver = naverLogin(state());
		assertThat(naver.status()).isEqualTo(200);
		assertThat(exchangeForm).contains("grant_type=authorization_code", "client_id=naver-app", "client_secret=local-secret", "code=code");
		assertThat(profileAuthorization).isEqualTo("Bearer provider-token");
		String nonce = appleNonce();
		Response apple = appleLogin(token("apple-user", nonce, "same@example.com"), nonce);
		assertThat(apple.status()).isEqualTo(200);
		assertThat(apple.user().get("id")).isNotEqualTo(naver.user().get("id"));
		assertThat(apple.user().get("profileRegistered")).isEqualTo(false);
		String absentNonce = appleNonce();
		Response absent = appleLogin(token("no-email", absentNonce, null), absentNonce);
		assertThat(absent.status()).isEqualTo(200);
		assertThat(absent.user().get("email")).isNull();
		String otherNonce = appleNonce();
		assertThat(appleLogin(token("no-email-2", otherNonce, null), otherNonce).status()).isEqualTo(200);
		profileResponse = "{\"resultcode\":\"00\",\"response\":{\"id\":\"naver-no-email\"}}";
		assertThat(naverLogin(state()).status()).isEqualTo(200);
		assertThat(users.count()).isEqualTo(5);
		String access = (String) apple.data().get("accessToken");
		assertThat(jwt.parseAccessToken(access).userId()).isEqualTo(((Number) apple.user().get("id")).longValue());
		assertThat(request(HttpMethod.GET, "/api/users/me", null, access).status()).isEqualTo(200);
		assertThat(request(HttpMethod.POST, "/api/users/me/profile", Map.of("nickname", "애플회원"), access).status()).isEqualTo(201);
		String refresh = (String) apple.data().get("refreshToken");
		Response rotated = post("/api/auth/token/refresh", Map.of("refreshToken", refresh));
		assertThat(rotated.status()).isEqualTo(200);
		assertThat(rotated.user().get("profileRegistered")).isEqualTo(true);
		assertThat(rotated.data().get("refreshToken")).isNotEqualTo(refresh);
		assertThat(post("/api/auth/token/refresh", Map.of("refreshToken", refresh)).status()).isEqualTo(401);
		String next = (String) rotated.data().get("refreshToken");
		assertThat(post("/api/auth/logout", Map.of("refreshToken", next)).status()).isEqualTo(200);
		assertThat(post("/api/auth/token/refresh", Map.of("refreshToken", next)).status()).isEqualTo(401);
	}
	@Test
	void withdrawnNaverAndAppleIdentitiesCannotLogInOrReuseConsumedChallenges() throws Exception {
		String naverState = state();
		Response naver = naverLogin(naverState);
		assertThat(naver.status()).isEqualTo(200);
		assertThat(request(HttpMethod.DELETE, "/api/users/me", null,
			(String) naver.data().get("accessToken")).status()).isEqualTo(200);
		assertThat(naverLogin(state()).status()).isEqualTo(401);
		assertThat(naverLogin(naverState).status()).isEqualTo(401);
		assertThat(users.count()).isEqualTo(1);

		String appleNonce = appleNonce();
		Response apple = appleLogin(token("withdrawn-apple", appleNonce, null), appleNonce);
		assertThat(apple.status()).isEqualTo(200);
		assertThat(request(HttpMethod.DELETE, "/api/users/me", null,
			(String) apple.data().get("accessToken")).status()).isEqualTo(200);
		String freshNonce = appleNonce();
		assertThat(appleLogin(token("withdrawn-apple", freshNonce, null), freshNonce).status()).isEqualTo(401);
		assertThat(appleLogin(token("withdrawn-apple", freshNonce, null), freshNonce).status()).isEqualTo(401);
		assertThat(users.count()).isEqualTo(2);
		assertThat(refreshTokens.count()).isZero();
	}

	@Test
	void appleRejectsInvalidSignatureIssuerAudienceExpiryClaimsAndMissingNonce() throws Exception {
		String nonce = appleNonce();
		Instant now = Instant.now();
		for (String invalid : List.of(
			sign(KEY, "apple-user", nonce, "https://evil.example", "apple-app", now.plusSeconds(300), now.minusSeconds(1), null),
			sign(KEY, "apple-user", nonce, "https://appleid.apple.com", "other-app", now.plusSeconds(300), now.minusSeconds(1), null),
			sign(KEY, "apple-user", nonce, "https://appleid.apple.com", null, now.plusSeconds(300), now.minusSeconds(1), null),
			sign(KEY, "apple-user", nonce, "https://appleid.apple.com", "apple-app", now.plusSeconds(300), null, null),
			sign(KEY, "apple-user", "wrong-nonce", "https://appleid.apple.com", "apple-app", now.plusSeconds(300), now.minusSeconds(1), null),
			sign(KEY, "apple-user", nonce, "https://appleid.apple.com", "apple-app", now.minusSeconds(1), now.minusSeconds(5), null),
			sign(KEY, "apple-user", nonce, "https://appleid.apple.com", "apple-app", null, now.minusSeconds(1), null),
			sign(KEY, "apple-user", nonce, "https://appleid.apple.com", "apple-app", now.plusSeconds(300), now.plusSeconds(100), null),
			sign(KEY, "", nonce, "https://appleid.apple.com", "apple-app", now.plusSeconds(300), now.minusSeconds(1), null),
			sign(KEY, "apple-user", null, "https://appleid.apple.com", "apple-app", now.plusSeconds(300), now.minusSeconds(1), null),
			sign(new RSAKeyGenerator(2048).keyID("local-key").generate(), "apple-user", nonce,
				"https://appleid.apple.com", "apple-app", now.plusSeconds(300), now.minusSeconds(1), null),
			"broken.jwt.token")) {
			assertThat(appleLogin(invalid, nonce).status()).isEqualTo(401);
		}
		assertThat(users.count()).isZero(); assertThat(refreshTokens.count()).isZero();
		assertThat(appleLogin(token("apple-user", nonce, null), nonce).status()).isEqualTo(200);
		assertThat(appleLogin(token("apple-user", nonce, null), nonce).status()).isEqualTo(401);
	}
	@Test
	void challengesAreProviderBoundExpiredAndValidatedBeforeNaverExchange() throws Exception {
		String state = state();
		assertThat(appleLogin(token("apple-user", state, null), state).status()).isEqualTo(401);
		String nonce = appleNonce();
		assertThat(naverLogin(nonce).status()).isEqualTo(401);
		assertThat(EXCHANGES.get()).isZero();
		var expired = nonces.findAll().stream().filter(n -> n.getNonceHash().equals(JwtTokenProvider.hash("APPLE:" + nonce))).findFirst().orElseThrow();
		// 실제 DB 만료 상태를 만들어 nonce 수명의 경계를 검증한다.
		try (var connection = DriverManager.getConnection(dbUrl(), postgres() ? "issue53" : "sa", "");
			var sql = connection.prepareStatement("UPDATE login_nonces SET expires_at = ? WHERE id = ?")) {
			sql.setObject(1, LocalDateTime.now().minusSeconds(1)); sql.setLong(2, expired.getId()); sql.executeUpdate();
		}
		assertThat(appleLogin(token("apple-user", nonce, null), nonce).status()).isEqualTo(401);
		assertThat(post("/api/auth/apple/mobile-login", Map.of("idToken", "", "nonce", nonce)).status()).isEqualTo(400);
		assertThat(post("/api/auth/naver/mobile-login", Map.of("authorizationCode", "code", "state", "short")).status()).isEqualTo(400);
		assertThat(naverLogin(state).status()).isEqualTo(200);
		assertThat(naverLogin(state).status()).isEqualTo(401);
		assertThat(EXCHANGES.get()).isEqualTo(1);
	}
	@Test
	void naverRejectsProviderErrorsExpiredTokenMalformedProfileAndMissingSubject() {
		String state = state();
		for (String invalid : List.of("{\"error\":\"invalid_grant\"}", "{}", "not-json",
			"{\"access_token\":\"token\",\"token_type\":\"bearer\",\"expires_in\":\"0\"}",
			"{\"access_token\":\"token\",\"token_type\":\"MAC\",\"expires_in\":\"3600\"}")) {
			tokenResponse = invalid; assertThat(naverLogin(state).status()).isEqualTo(401);
		}
		reset(); state = state(); tokenStatus = 401;
		assertThat(naverLogin(state).status()).isEqualTo(401); tokenStatus = 200;
		for (String invalid : List.of("{\"resultcode\":\"024\"}", "{}", "not-json", "{\"resultcode\":\"00\",\"response\":{}}")) {
			profileResponse = invalid; assertThat(naverLogin(state).status()).isEqualTo(401);
		}
		assertThat(users.count()).isZero(); assertThat(refreshTokens.count()).isZero();
	}
	@Test
	void concurrentNewIdentityUsesOneUserAndConcurrentNonceReplaySucceedsOnce() throws Exception {
		String firstNonce = appleNonce(), secondNonce = appleNonce();
		var barrier = new CyclicBarrier(2); var calls = new AtomicInteger();
		doAnswer(invocation -> {
			@SuppressWarnings("unchecked") Optional<User> found = (Optional<User>) invocation.callRealMethod();
			if (calls.incrementAndGet() <= 2) { assertThat(found).isEmpty(); barrier.await(10, TimeUnit.SECONDS); }
			return found;
		}).when(socialUsers).find(org.mockito.ArgumentMatchers.any(SocialIdentity.class));
		String firstToken = token("racing-user", firstNonce, "same@example.com");
		String secondToken = token("racing-user", secondNonce, "same@example.com");
		List<Response> created = concurrently(() -> appleLogin(firstToken, firstNonce), () -> appleLogin(secondToken, secondNonce));
		assertThat(created).extracting(Response::status).containsExactly(200, 200);
		assertThat(created.get(0).user().get("id")).isEqualTo(created.get(1).user().get("id"));
		assertThat(users.count()).isEqualTo(1); assertThat(refreshTokens.count()).isEqualTo(2);
		String replayNonce = appleNonce(); String replayToken = token("racing-user", replayNonce, null);
		assertThat(concurrently(() -> appleLogin(replayToken, replayNonce), () -> appleLogin(replayToken, replayNonce)))
			.extracting(Response::status).containsExactlyInAnyOrder(200, 401);
	}
	@Test
	void kakaoStillUsesRealJwksNonceAndKeepsItsEmailConsentContract() throws Exception {
		String nonce = (String) post("/api/auth/kakao/nonce", null).data().get("nonce");
		String idToken = sign(KEY, "kakao-user", nonce, "https://kauth.kakao.com", "kakao-app",
			Instant.now().plusSeconds(300), Instant.now().minusSeconds(1), "same@example.com");
		Response login = post("/api/auth/kakao/mobile-login", Map.of("idToken", idToken, "nonce", nonce));
		assertThat(login.status()).isEqualTo(200); assertThat(login.user().get("profileRegistered")).isEqualTo(false);
		assertThat(post("/api/auth/kakao/mobile-login", Map.of("idToken", idToken, "nonce", nonce)).status()).isEqualTo(401);
		String absent = (String) post("/api/auth/kakao/nonce", null).data().get("nonce");
		assertThat(post("/api/auth/kakao/mobile-login", Map.of("idToken", sign(KEY, "kakao-no-email", absent,
			"https://kauth.kakao.com", "kakao-app", Instant.now().plusSeconds(300), Instant.now().minusSeconds(1), null), "nonce", absent))
			.status()).isEqualTo(400);
	}
	@Test
	void appleRejectsJwksOutageUnknownKidAndAcceptsRotatedTrustedKey() throws Exception {
		RSAKey rotated = new RSAKeyGenerator(2048).keyID("rotated-key").generate();
		String nonce = appleNonce();
		String token = sign(rotated, "rotated-user", nonce, "https://appleid.apple.com", "apple-app",
			Instant.now().plusSeconds(300), Instant.now().minusSeconds(1), null);
		jwksStatus = 503;
		assertThat(appleLogin(token, nonce).status()).isEqualTo(401);
		jwksStatus = 200;
		assertThat(appleLogin(token, nonce).status()).isEqualTo(401);
		jwksBody = new JWKSet(List.of(KEY.toPublicJWK(), rotated.toPublicJWK())).toString();
		assertThat(appleLogin(token, nonce).status()).isEqualTo(200);
	}
	@Test
	void postgresMigrationRollsBackIfStandaloneEmailIndexNeedsReview() throws Exception {
		org.junit.jupiter.api.Assumptions.assumeTrue(postgres(), "PostgreSQL 전용 SQL 검증");
		try (var connection = DriverManager.getConnection(dbUrl(), "issue53", ""); var sql = connection.createStatement()) {
			sql.execute("ALTER TABLE users ALTER COLUMN email SET NOT NULL");
			sql.execute("CREATE UNIQUE INDEX issue53_standalone_email ON users (email)");
			try {
				org.assertj.core.api.Assertions.assertThatThrownBy(() -> sql.execute(
					Files.readString(Path.of("src/main/resources/db/manual/053_social_login.sql"))))
					.isInstanceOf(java.sql.SQLException.class).hasMessageContaining("Review standalone unique email index");
				sql.execute("ROLLBACK");
				try (var result = sql.executeQuery("SELECT attnotnull FROM pg_attribute WHERE attrelid = 'users'::regclass AND attname = 'email'")) {
					result.next(); assertThat(result.getBoolean(1)).isTrue();
				}
			} finally {
				sql.execute("ROLLBACK"); sql.execute("DROP INDEX issue53_standalone_email");
				sql.execute("ALTER TABLE users ALTER COLUMN email DROP NOT NULL");
			}
		}
	}
	private String appleNonce() { return (String) post("/api/auth/apple/nonce", null).data().get("nonce"); }
	private String state() {
		Response response = post("/api/auth/naver/state", null); assertThat(response.status()).isEqualTo(200);
		String state = (String) response.data().get("state");
		assertThat(response.data().get("authorizationUrl").toString()).contains("client_id=naver-app", "state=" + state);
		return state;
	}
	private Response appleLogin(String token, String nonce) { return post("/api/auth/apple/mobile-login", Map.of("idToken", token, "nonce", nonce)); }
	private Response naverLogin(String state) { return post("/api/auth/naver/mobile-login", Map.of("authorizationCode", "code", "state", state)); }
	private String token(String subject, String nonce, String email) throws Exception {
		return sign(KEY, subject, nonce, "https://appleid.apple.com", "apple-app", Instant.now().plusSeconds(300), Instant.now().minusSeconds(1), email);
	}
	private String sign(RSAKey key, String subject, String nonce, String issuer, String audience,
		Instant expiry, Instant issuedAt, String email) throws Exception {
		var claims = new JWTClaimsSet.Builder().issuer(issuer).audience(audience).subject(subject);
		if (nonce != null) claims.claim("nonce", nonce);
		if (expiry != null) claims.expirationTime(Date.from(expiry));
		if (issuedAt != null) claims.issueTime(Date.from(issuedAt));
		if (email != null) claims.claim("email", email).claim("email_verified", true);
		SignedJWT token = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(), claims.build());
		token.sign(new RSASSASigner(key)); return token.serialize();
	}
	private Response post(String path, Map<String, Object> body) { return request(HttpMethod.POST, path, body, null); }
	private Response request(HttpMethod method, String path, Map<String, Object> body, String token) {
		var request = RestClient.create("http://127.0.0.1:" + port).method(method).uri(path).contentType(MediaType.APPLICATION_JSON);
		if (body != null) request.body(body);
		if (token != null) request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
		return request.exchange((req, response) -> new Response(response.getStatusCode().value(), response.bodyTo(Map.class)));
	}
	private List<Response> concurrently(Callable<Response> first, Callable<Response> second) throws Exception {
		try (var executor = Executors.newFixedThreadPool(2)) {
			var start = new CyclicBarrier(2);
			var a = executor.submit(() -> { start.await(10, TimeUnit.SECONDS); return first.call(); });
			var b = executor.submit(() -> { start.await(10, TimeUnit.SECONDS); return second.call(); });
			return List.of(a.get(30, TimeUnit.SECONDS), b.get(30, TimeUnit.SECONDS));
		}
	}
	private record Response(int status, Map<?, ?> body) {
		Map<?, ?> data() { return (Map<?, ?>) body.get("data"); }
		Map<?, ?> user() { return (Map<?, ?>) data().get("user"); }
	}
}
