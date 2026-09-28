// 실제 인증 필터를 거쳐 내 정보와 프로필 API 계약을 검증한다.
package com.kitschcatch.backend.domain.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;

import com.kitschcatch.backend.domain.user.entity.AuthProvider;
import com.kitschcatch.backend.domain.user.entity.User;
import com.kitschcatch.backend.domain.user.repository.UserRepository;
import com.kitschcatch.backend.domain.user.service.ProfileImageStorage;
import com.kitschcatch.backend.global.security.JwtTokenProvider;
import com.kitschcatch.backend.global.security.JwtProperties;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import java.util.Map;
import java.util.HashMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.HttpClientErrorException;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
	"spring.datasource.url=jdbc:h2:mem:user-profile-http;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
	"spring.datasource.driver-class-name=org.h2.Driver",
	"spring.datasource.username=sa",
	"spring.datasource.password=",
	"spring.jpa.hibernate.ddl-auto=create-drop",
	"kakao.oauth.native-app-key=test-native-app-key",
	"app.orders.expiration-enabled=false",
	"springdoc.api-docs.enabled=true"
})
class UserProfileHttpTest {

	@LocalServerPort
	private int port;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private JwtTokenProvider jwtTokenProvider;
	@Autowired
	private JwtProperties jwtProperties;

	@MockitoBean
	private ProfileImageStorage profileImageStorage;

	@BeforeEach
	void setUp() {
		userRepository.deleteAllInBatch();
	}

	@Test
	void userCanRegisterAndUpdateOwnProfile() {
		User user = userRepository.save(user("kakao-default", "user-1"));
		RestClient client = client(user.getId());

		Map<?, ?> registered = data(client.post()
			.uri("/api/users/me/profile")
			.body(Map.of("nickname", "collector"))
			.retrieve()
			.body(Map.class));

		assertThat(registered.get("nickname")).isEqualTo("collector");

		Map<String, Object> clearImage = new HashMap<>();
		clearImage.put("profileImageKey", null);
		Map<?, ?> updated = data(client.patch()
			.uri("/api/users/me")
			.body(clearImage)
			.retrieve()
			.body(Map.class));

		assertThat(updated.get("nickname")).isEqualTo("collector");
		assertThat(updated.get("profileImageKey")).isNull();
	}

	@Test
	void profileImageMustBelongToAuthenticatedUserAndExist() {
		User user = userRepository.save(user("kakao-default", "user-2"));
		RestClient client = client(user.getId());
		String imageKey = "profiles/" + user.getId() + "/image.png";
		when(profileImageStorage.metadata(imageKey)).thenReturn(java.util.Optional.empty());

		org.assertj.core.api.Assertions.assertThatThrownBy(() -> client.post()
			.uri("/api/users/me/profile")
			.body(Map.of("nickname", "collector", "profileImageKey", imageKey))
			.retrieve()
			.toEntity(Map.class))
			.isInstanceOf(HttpClientErrorException.BadRequest.class)
			.satisfies(exception -> assertThat(((HttpClientErrorException) exception).getResponseBodyAsString())
				.contains("USER_006"));
	}

	@Test
	void registrationAndUpdateValidateNicknameAfterNormalization() {
		User user = userRepository.save(user("kakao-default", "normalization-user"));
		RestClient client = client(user.getId());
		String fiftyCharacters = "가".repeat(50);
		String decomposed = "가".repeat(50);

		Map<?, ?> registered = data(client.post().uri("/api/users/me/profile")
			.body(Map.of("nickname", " " + decomposed + " ")).retrieve().body(Map.class));
		assertThat(registered.get("nickname")).isEqualTo(fiftyCharacters);

		Map<?, ?> updated = data(client.patch().uri("/api/users/me")
			.body(Map.of("nickname", " " + "나".repeat(50) + " ")).retrieve().body(Map.class));
		assertThat(updated.get("nickname")).isEqualTo("나".repeat(50));

		org.assertj.core.api.Assertions.assertThatThrownBy(() -> client.patch().uri("/api/users/me")
			.body(Map.of("nickname", "나".repeat(51))).retrieve().toEntity(Map.class))
			.isInstanceOf(HttpClientErrorException.BadRequest.class)
			.satisfies(error -> assertThat(((HttpClientErrorException) error).getResponseBodyAsString())
				.contains("COMMON_001"));
		assertThat(userRepository.findById(user.getId()).orElseThrow().getNickname()).isEqualTo("나".repeat(50));
	}

	@Test
	void nicknameAvailabilityHandlesUnregisteredUsersDuplicatesAndOwnNicknameWithoutMutation() {
		User first = userRepository.saveAndFlush(user("카카오기본닉네임", "availability-first"));
		User second = userRepository.saveAndFlush(user("카카오기본닉네임", "availability-second"));
		assertThat(availability(first.getId(), "카카오기본닉네임").data().get("available")).isEqualTo(true);
		assertThat(availability(second.getId(), "카카오기본닉네임").data().get("available")).isEqualTo(true);

		assertThat(request(second.getId(), "/api/users/me/profile", HttpMethod.POST,
			Map.of("nickname", "가"), null).status()).isEqualTo(201);
		User before = userRepository.findById(second.getId()).orElseThrow();
		var occupied = availability(first.getId(), " 가 ");
		assertThat(occupied.status()).isEqualTo(200);
		assertThat(occupied.body().containsKey("error")).isFalse();
		assertThat(occupied.data().get("nickname")).isEqualTo("가");
		assertThat(occupied.data().get("available")).isEqualTo(false);
		assertThat(availability(second.getId(), "가").data().get("available")).isEqualTo(true);
		assertThat(availability(first.getId(), "가+").data().get("available")).isEqualTo(true);
		assertThat(availability(first.getId(), "가 ").data().get("available")).isEqualTo(false);
		User after = userRepository.findById(second.getId()).orElseThrow();
		assertThat(after.getNickname()).isEqualTo(before.getNickname());
		assertThat(after.getNicknameKey()).isEqualTo(before.getNicknameKey());
		assertThat(after.getProfileRegisteredAt()).isEqualTo(before.getProfileRegisteredAt());
		verifyNoInteractions(profileImageStorage);
	}

	@Test
	void nicknameAvailabilityReturnsCommonInputAuthenticationAndUserErrors() {
		User user = userRepository.saveAndFlush(user("default", "availability-errors"));
		assertError(request(user.getId(), "/api/users/nickname-availability", HttpMethod.GET, null, null), 400, "COMMON_002");
		for (String invalid : new String[]{"", " ", "가".repeat(51)}) {
			assertError(availability(user.getId(), invalid), 400, "COMMON_001");
		}
		assertError(availability(Long.MAX_VALUE, "valid"), 404, "USER_001");
		String path = "/api/users/nickname-availability?nickname=valid";
		for (String header : new String[]{"", "Bearer invalid", "Bearer " + jwtTokenProvider.createRefreshToken(user.getId()),
			"Bearer " + expiredToken(user.getId())}) {
			assertError(request(user.getId(), path, HttpMethod.GET, null, header), 401, "AUTH_004");
		}
	}

	@Test
	void nicknameAvailabilityIsPublishedInOpenApi() {
		Map<?, ?> document = RestClient.create("http://127.0.0.1:" + port).get().uri("/v3/api-docs")
			.retrieve().body(Map.class);
		Map<?, ?> path = (Map<?, ?>) ((Map<?, ?>) document.get("paths")).get("/api/users/nickname-availability");
		Map<?, ?> operation = (Map<?, ?>) path.get("get");
		assertThat(((Map<?, ?>) ((java.util.List<?>) operation.get("parameters")).getFirst()).get("required")).isEqualTo(true);
		assertThat(operation.get("security")).isNotNull();
		Map<?, ?> schemas = (Map<?, ?>) ((Map<?, ?>) document.get("components")).get("schemas");
		Map<?, ?> response = (Map<?, ?>) schemas.get("NicknameAvailabilityResponse");
		assertThat(((Map<?, ?>) response.get("properties")).keySet().stream().map(Object::toString).toList())
			.contains("nickname", "available");
	}

	private Response availability(Long userId, String nickname) {
		return request(userId, "/api/users/nickname-availability?nickname=" +
			java.net.URLEncoder.encode(nickname, StandardCharsets.UTF_8), HttpMethod.GET, null, null);
	}

	private Response request(Long userId, String path, HttpMethod method, Object body, String authorization) {
		var request = RestClient.create().method(method)
			.uri(java.net.URI.create("http://127.0.0.1:" + port + path));
		String header = authorization == null ? "Bearer " + jwtTokenProvider.createAccessToken(userId) : authorization;
		if (!header.isEmpty()) {
			request.header(HttpHeaders.AUTHORIZATION, header);
		}
		if (body != null) {
			request.contentType(MediaType.APPLICATION_JSON).body(body);
		}
		return request.exchange((sent, response) -> new Response(response.getStatusCode().value(), response.bodyTo(Map.class)));
	}

	private String expiredToken(Long userId) {
		var key = new SecretKeySpec(jwtProperties.secret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
		var encoder = new NimbusJwtEncoder(new ImmutableSecret<>(key));
		var claims = JwtClaimsSet.builder().issuer("kitschcatch").subject(userId.toString()).claim("type", "access")
			.issuedAt(Instant.now().minusSeconds(600)).expiresAt(Instant.now().minusSeconds(120)).build();
		return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
	}

	private void assertError(Response response, int status, String code) {
		assertThat(response.status()).isEqualTo(status);
		assertThat(((Map<?, ?>) response.body().get("error")).get("code")).isEqualTo(code);
	}

	private record Response(int status, Map<?, ?> body) {
		Map<?, ?> data() {
			return (Map<?, ?>) body.get("data");
		}
	}

	private RestClient client(Long userId) {
		return RestClient.builder()
			.baseUrl("http://127.0.0.1:" + port)
			.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + jwtTokenProvider.createAccessToken(userId))
			.defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
			.build();
	}

	private Map<?, ?> data(Map<?, ?> response) {
		assertThat(response.get("success")).isEqualTo(true);
		return (Map<?, ?>) response.get("data");
	}

	private User user(String nickname, String providerUserId) {
		return User.builder()
			.nickname(nickname)
			.email(providerUserId + "@example.com")
			.authProvider(AuthProvider.KAKAO)
			.providerUserId(providerUserId)
			.build();
	}
}
