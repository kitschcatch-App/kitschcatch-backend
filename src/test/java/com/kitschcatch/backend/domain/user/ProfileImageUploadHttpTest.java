// 실제 HTTP·JWT·JSON·S3 서명을 연결해 프로필 이미지 발급 계약을 검증한다.
package com.kitschcatch.backend.domain.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;

import com.kitschcatch.backend.domain.user.entity.AuthProvider;
import com.kitschcatch.backend.domain.user.entity.User;
import com.kitschcatch.backend.domain.user.repository.UserRepository;
import com.kitschcatch.backend.global.security.JwtProperties;
import com.kitschcatch.backend.global.security.JwtTokenProvider;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.stream.Stream;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.client.RestClient;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
	"spring.datasource.url=jdbc:h2:mem:profile-image-http;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
	"spring.datasource.driver-class-name=org.h2.Driver",
	"spring.datasource.username=sa", "spring.datasource.password=",
	"spring.jpa.hibernate.ddl-auto=create-drop", "kakao.oauth.native-app-key=test-native-app-key",
	"app.orders.expiration-enabled=false", "app.payments.recovery.enabled=false",
	"app.s3.bucket=test-bucket", "app.s3.public-base-url=https://cdn.example.com",
	"springdoc.api-docs.enabled=true"
})
class ProfileImageUploadHttpTest {
	private static final String PATH = "/api/users/me/profile/image/presigned-url";
	private static final String VALID = "{\"fileName\":\"profile.png\",\"contentType\":\"image/png\",\"fileSize\":1024}";
	@LocalServerPort private int port;
	@Autowired private UserRepository users;
	@Autowired private JwtTokenProvider tokens;
	@Autowired private JwtProperties jwtProperties;
	@MockitoBean private S3Client s3Client;
	@TestBean(methodName = "testPresigner") private S3Presigner s3Presigner;
	private User user;

	static S3Presigner testPresigner() {
		return S3Presigner.builder().region(Region.AP_NORTHEAST_2)
			.credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test-access", "test-secret")))
			.build();
	}

	@BeforeEach
	void setUp() {
		users.deleteAllInBatch();
		user = users.saveAndFlush(User.builder().nickname("카카오닉네임").email("profile-test@example.com")
			.authProvider(AuthProvider.KAKAO).providerUserId("profile-test").build());
	}

	@Test
	void issuesUniqueUrlsBeforeAndAfterRegistrationWithoutChangingProfile() {
		Response first = issue(VALID);
		assertThat(first.status()).isEqualTo(200);
		assertThat(first.body().get("success")).isEqualTo(true);
		assertThat(first.data().get("imageKey").toString()).matches("profiles/" + user.getId() + "/[0-9a-f-]{36}\\.png");
		assertThat(first.data().get("imageUrl")).isEqualTo("https://cdn.example.com/" + first.data().get("imageKey"));
		assertThat(first.data().get("expiresIn")).isEqualTo(600);
		assertThat(Instant.parse(first.data().get("expiresAt").toString())).isAfter(Instant.now());
		assertThat((Map<?, ?>) first.data().get("uploadHeaders")).isEqualTo(Map.of("content-type", "image/png", "if-none-match", "*"));
		assertThat(first.data().get("uploadUrl").toString().contains("X-Amz-Signature=")).isTrue();
		User unchanged = users.findById(user.getId()).orElseThrow();
		assertThat(unchanged.getNickname()).isEqualTo("카카오닉네임");
		assertThat(unchanged.getProfileImageKey()).isNull();
		assertThat(unchanged.getProfileRegisteredAt()).isNull();

		user.registerProfile("등록닉네임", "등록닉네임", null, Instant.now());
		users.saveAndFlush(user);
		Instant registeredAt = users.findById(user.getId()).orElseThrow().getProfileRegisteredAt();
		Response second = issue(VALID);
		assertThat(second.status()).isEqualTo(200);
		assertThat(second.data().get("imageKey")).isNotEqualTo(first.data().get("imageKey"));
		unchanged = users.findById(user.getId()).orElseThrow();
		assertThat(unchanged.getNickname()).isEqualTo("등록닉네임");
		assertThat(unchanged.getProfileRegisteredAt()).isEqualTo(registeredAt);
		assertThat(unchanged.getProfileImageKey()).isNull();
		verifyNoInteractions(s3Client);
	}

	@Test
	void requiresValidAccessTokenAndExistingUser() {
		for (String token : new String[]{"", "Bearer invalid", "Bearer " + tokens.createRefreshToken(user.getId()),
			"Bearer " + expiredToken()}) {
			assertError(request(token, VALID), 401, "AUTH_004");
		}
		users.deleteById(user.getId());
		assertError(issue(VALID), 404, "USER_001");
		verifyNoInteractions(s3Client);
	}

	@ParameterizedTest
	@MethodSource("invalidTypes")
	void rejectsUnknownFieldsAndJsonCoercion(String body) {
		assertError(issue(body), 400, "COMMON_002");
		verifyNoInteractions(s3Client);
	}

	static Stream<String> invalidTypes() {
		return Stream.of(VALID.replace("1024", "\"1024\""), VALID.replace("1024", "1.0"),
			VALID.replace("1024", "true"), VALID.replace("1024", "9223372036854775808"),
			VALID.replace("\"profile.png\"", "123"), VALID.replace("\"image/png\"", "[]"),
			VALID.replace("}", ",\"userId\":42}"), VALID.replace("}", ",\"expiresIn\":3600}"), "[]", "null", "{");
	}

	@ParameterizedTest
	@MethodSource("invalidValues")
	void rejectsMissingAndInvalidImageValues(String body) {
		assertError(issue(body), 400, "COMMON_001");
		verifyNoInteractions(s3Client);
	}

	static Stream<String> invalidValues() {
		return Stream.of("{}", VALID.replace("1024", "null"), VALID.replace("1024", "0"),
			VALID.replace("1024", "-1"), VALID.replace("1024", "5000001"),
			VALID.replace("profile.png", "../profile.png"), VALID.replace("profile.png", "profile.jpg"),
			VALID.replace("image/png", "application/octet-stream"), VALID.replace("profile.png", " "),
			VALID.replace("profile.png", "a".repeat(256) + ".png"), VALID.replace("image/png", "a".repeat(101)));
	}

	@Test
	void acceptsExactMaximumAndNormalizedMime() {
		assertThat(issue(VALID.replace("1024", "5000000").replace("image/png", " IMAGE/PNG ")).status()).isEqualTo(200);
	}

	@Test
	void publishesActualRequestAndResponseContractInOpenApi() {
		Map<?, ?> document = RestClient.create("http://127.0.0.1:" + port).get().uri("/v3/api-docs")
			.retrieve().body(Map.class);
		assertThat(((Map<?, ?>) document.get("paths")).containsKey(PATH)).isTrue();
		Map<?, ?> schemas = (Map<?, ?>) ((Map<?, ?>) document.get("components")).get("schemas");
		Map<?, ?> request = (Map<?, ?>) schemas.get("CreateProfileImageUploadUrlRequest");
		assertThat(((Map<?, ?>) request.get("properties")).keySet().stream().map(Object::toString).toList())
			.containsExactlyInAnyOrder("fileName", "contentType", "fileSize");
		Map<?, ?> response = (Map<?, ?>) schemas.get("ProfileImageUploadUrlResponse");
		assertThat(((Map<?, ?>) response.get("properties")).keySet().stream().map(Object::toString).toList())
			.contains("uploadUrl", "imageKey", "imageUrl", "expiresAt", "expiresIn", "uploadHeaders");
	}

	private String expiredToken() {
		var key = new SecretKeySpec(jwtProperties.secret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
		var encoder = new NimbusJwtEncoder(new ImmutableSecret<>(key));
		var claims = JwtClaimsSet.builder().issuer("kitschcatch").subject(user.getId().toString()).claim("type", "access")
			.issuedAt(Instant.now().minusSeconds(600)).expiresAt(Instant.now().minusSeconds(120)).build();
		return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
	}

	private Response issue(String body) {
		return request("Bearer " + tokens.createAccessToken(user.getId()), body);
	}

	private Response request(String authorization, String body) {
		return RestClient.create("http://127.0.0.1:" + port).post().uri(PATH)
			.header(HttpHeaders.AUTHORIZATION, authorization).contentType(MediaType.APPLICATION_JSON).body(body)
			.exchange((request, response) -> new Response(response.getStatusCode().value(), response.bodyTo(Map.class)));
	}

	private void assertError(Response response, int status, String code) {
		assertThat(response.status()).isEqualTo(status);
		assertThat(((Map<?, ?>) response.body().get("error")).get("code")).isEqualTo(code);
	}

	private record Response(int status, Map<?, ?> body) {
		Map<?, ?> data() { return (Map<?, ?>) body.get("data"); }
	}
}
