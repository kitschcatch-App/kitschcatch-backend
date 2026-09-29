// 실제 HTTP·인증 토큰·프로필 저장을 연결해 인증 응답의 등록 상태를 검증한다.
package com.kitschcatch.backend.domain.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.kitschcatch.backend.domain.auth.oidc.KakaoOidcTokenVerifier;
import com.kitschcatch.backend.domain.auth.oidc.KakaoOidcUser;
import com.kitschcatch.backend.domain.auth.repository.LoginNonceRepository;
import com.kitschcatch.backend.domain.auth.repository.RefreshTokenRepository;
import com.kitschcatch.backend.domain.user.repository.UserRepository;
import com.kitschcatch.backend.domain.user.service.ProfileImageMetadata;
import com.kitschcatch.backend.domain.user.service.ProfileImageStorage;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.client.RestClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
	"spring.datasource.url=jdbc:h2:mem:auth-profile-registration-http;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
	"spring.datasource.driver-class-name=org.h2.Driver",
	"spring.datasource.username=sa", "spring.datasource.password=",
	"spring.jpa.hibernate.ddl-auto=create-drop", "kakao.oauth.native-app-key=test-native-app-key",
	"app.orders.expiration-enabled=false", "app.payments.recovery.enabled=false",
	"springdoc.api-docs.enabled=true"
})
class AuthProfileRegistrationHttpTest {

	@LocalServerPort private int port;
	@Autowired private UserRepository userRepository;
	@Autowired private RefreshTokenRepository refreshTokenRepository;
	@Autowired private LoginNonceRepository loginNonceRepository;
	@MockitoBean private KakaoOidcTokenVerifier kakaoOidcTokenVerifier;
	@MockitoBean private ProfileImageStorage profileImageStorage;

	@BeforeEach
	void setUp() {
		refreshTokenRepository.deleteAllInBatch();
		loginNonceRepository.deleteAllInBatch();
		userRepository.deleteAllInBatch();
		when(kakaoOidcTokenVerifier.verify(anyString(), anyString()))
			.thenReturn(new KakaoOidcUser("kakao-37", "profile@example.com", "카카오기본닉네임"));
	}

	@Test
	void loginRegistrationRefreshAndProfileChangesReturnCurrentRegistrationState() {
		Response login = login();
		assertThat(login.status()).isEqualTo(200);
		assertThat(login.body().get("success")).isEqualTo(true);
		assertThat(login.body().containsKey("error")).isFalse();
		assertThat(login.data().get("tokenType")).isEqualTo("Bearer");
		assertThat(login.data().get("accessToken")).isInstanceOf(String.class);
		assertThat(login.data().get("refreshToken")).isInstanceOf(String.class);
		assertThat(login.user().get("email")).isEqualTo("profile@example.com");
		assertThat(login.user().get("nickname")).isEqualTo("카카오기본닉네임");
		assertThat(login.user().get("profileRegistered")).isEqualTo(false);
		long userId = ((Number) login.user().get("id")).longValue();

		Response beforeRegistration = refresh(login.data().get("refreshToken").toString());
		assertThat(beforeRegistration.status()).isEqualTo(200);
		assertThat(beforeRegistration.user().get("profileRegistered")).isEqualTo(false);
		assertThat(beforeRegistration.data().get("refreshToken")).isNotEqualTo(login.data().get("refreshToken"));
		assertError(refresh(login.data().get("refreshToken").toString()), 401, "AUTH_003");

		String accessToken = login.data().get("accessToken").toString();
		Response registered = request(HttpMethod.POST, "/api/users/me/profile",
			Map.of("nickname", "collector"), accessToken);
		assertThat(registered.status()).isEqualTo(201);
		Object registeredAt = registered.data().get("profileRegisteredAt");
		assertThat(registeredAt).isNotNull();
		Response me = request(HttpMethod.GET, "/api/users/me", null, accessToken);
		assertThat(me.data().get("profileRegisteredAt")).isEqualTo(registeredAt);

		Response afterRegistration = refresh(beforeRegistration.data().get("refreshToken").toString());
		assertThat(afterRegistration.status()).isEqualTo(200);
		assertThat(afterRegistration.user().get("profileRegistered")).isEqualTo(true);
		assertThat(afterRegistration.user().get("nickname")).isEqualTo("collector");
		assertThat(login.user().get("profileRegistered")).isEqualTo(false);
		assertThat(login().user().get("profileRegistered")).isEqualTo(true);

		String imageKey = "profiles/" + userId + "/picture.png";
		when(profileImageStorage.metadata(imageKey))
			.thenReturn(Optional.of(new ProfileImageMetadata("image/png", 1024)));
		when(profileImageStorage.imageUrl(imageKey))
			.thenReturn("https://cdn.example.com/" + imageKey);
		Response renamed = request(HttpMethod.PATCH, "/api/users/me", Map.of("nickname", "renamed"), accessToken);
		assertThat(renamed.status()).isEqualTo(200);
		assertThat(renamed.data().get("profileRegisteredAt")).isEqualTo(registeredAt);
		Response withImage = request(HttpMethod.PATCH, "/api/users/me",
			Map.of("profileImageKey", imageKey), accessToken);
		assertThat(withImage.status()).isEqualTo(200);
		assertThat(withImage.data().get("profileImageUrl")).isEqualTo("https://cdn.example.com/" + imageKey);
		Response cleared = request(HttpMethod.PATCH, "/api/users/me",
			"{\"profileImageKey\":null}", accessToken);
		assertThat(cleared.status()).isEqualTo(200);
		assertThat(cleared.data().get("profileRegisteredAt")).isEqualTo(registeredAt);
		assertThat(cleared.data().get("profileImageKey")).isNull();
		Response afterChanges = refresh(afterRegistration.data().get("refreshToken").toString());
		assertThat(afterChanges.user().get("profileRegistered")).isEqualTo(true);
		assertThat(afterChanges.user().get("nickname")).isEqualTo("renamed");
		assertThat(userRepository.findById(userId).orElseThrow().getProfileRegisteredAt().toString())
			.isEqualTo(registeredAt);
	}

	@Test
	void failedProfileWritesDoNotChangeRegistrationStateInAuthResponse() {
		Response login = login();
		String accessToken = login.data().get("accessToken").toString();
		Response invalidRegistration = request(HttpMethod.POST, "/api/users/me/profile",
			Map.of("nickname", " "), accessToken);
		assertError(invalidRegistration, 400, "COMMON_001");
		Response stillUnregistered = refresh(login.data().get("refreshToken").toString());
		assertThat(stillUnregistered.user().get("profileRegistered")).isEqualTo(false);

		Response registered = request(HttpMethod.POST, "/api/users/me/profile",
			Map.of("nickname", "collector"), accessToken);
		assertThat(registered.status()).isEqualTo(201);
		Response invalidUpdate = request(HttpMethod.PATCH, "/api/users/me",
			Map.of("nickname", " "), accessToken);
		assertError(invalidUpdate, 400, "COMMON_001");
		Response stillRegistered = refresh(stillUnregistered.data().get("refreshToken").toString());
		assertThat(stillRegistered.user().get("profileRegistered")).isEqualTo(true);
		assertThat(stillRegistered.user().get("nickname")).isEqualTo("collector");
	}

	@Test
	void openApiPublishesBooleanProfileRegistrationInBothAuthResponses() {
		Map<?, ?> document = RestClient.create(baseUrl()).get().uri("/v3/api-docs")
			.retrieve().body(Map.class);
		Map<?, ?> paths = (Map<?, ?>) document.get("paths");
		Map<?, ?> schemas = (Map<?, ?>) ((Map<?, ?>) document.get("components")).get("schemas");
		Map<?, ?> authUser = (Map<?, ?>) schemas.get("AuthUserResponse");
		Map<?, ?> profileRegistered = (Map<?, ?>) ((Map<?, ?>) authUser.get("properties"))
			.get("profileRegistered");
		assertThat(profileRegistered.get("type")).isEqualTo("boolean");
		assertThat(profileRegistered.get("description").toString()).contains("프로필 등록");
		Map<?, ?> tokenResponse = (Map<?, ?>) schemas.get("AuthTokenResponse");
		Map<?, ?> user = (Map<?, ?>) ((Map<?, ?>) tokenResponse.get("properties")).get("user");
		assertThat(user.get("$ref")).isEqualTo("#/components/schemas/AuthUserResponse");
		for (String path : new String[]{"/api/auth/kakao/mobile-login", "/api/auth/token/refresh"}) {
			Map<?, ?> operation = (Map<?, ?>) ((Map<?, ?>) paths.get(path)).get("post");
			Map<?, ?> success = (Map<?, ?>) ((Map<?, ?>) operation.get("responses")).get("200");
			Map<?, ?> content = (Map<?, ?>) success.get("content");
			assertThat(content).isNotEmpty();
			Map<?, ?> json = (Map<?, ?>) content.values().iterator().next();
			Map<?, ?> responseSchema = (Map<?, ?>) json.get("schema");
			assertThat(responseSchema.toString()).contains("AuthTokenResponse");
		}
	}

	private Response login() {
		Response nonce = request(HttpMethod.POST, "/api/auth/kakao/nonce", null, null);
		assertThat(nonce.status()).isEqualTo(200);
		return request(HttpMethod.POST, "/api/auth/kakao/mobile-login",
			Map.of("idToken", "kakao-sdk-id-token", "nonce", nonce.data().get("nonce")), null);
	}

	private Response refresh(String refreshToken) {
		return request(HttpMethod.POST, "/api/auth/token/refresh", Map.of("refreshToken", refreshToken), null);
	}

	private Response request(HttpMethod method, String path, Object body, String accessToken) {
		var request = RestClient.create(baseUrl()).method(method).uri(path);
		if (accessToken != null) {
			request.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken);
		}
		if (body != null) {
			request.contentType(MediaType.APPLICATION_JSON).body(body);
		}
		return request.exchange((sent, response) ->
			new Response(response.getStatusCode().value(), response.bodyTo(Map.class)));
	}

	private String baseUrl() {
		return "http://127.0.0.1:" + port;
	}

	private void assertError(Response response, int status, String code) {
		assertThat(response.status()).isEqualTo(status);
		assertThat(response.body().get("success")).isEqualTo(false);
		assertThat(((Map<?, ?>) response.body().get("error")).get("code")).isEqualTo(code);
	}

	private record Response(int status, Map<?, ?> body) {
		Map<?, ?> data() {
			return (Map<?, ?>) body.get("data");
		}

		Map<?, ?> user() {
			return (Map<?, ?>) data().get("user");
		}
	}
}
