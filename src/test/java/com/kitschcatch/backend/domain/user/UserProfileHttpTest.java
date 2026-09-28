// 실제 인증 필터를 거쳐 내 정보와 프로필 API 계약을 검증한다.
package com.kitschcatch.backend.domain.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.kitschcatch.backend.domain.user.entity.AuthProvider;
import com.kitschcatch.backend.domain.user.entity.User;
import com.kitschcatch.backend.domain.user.repository.UserRepository;
import com.kitschcatch.backend.domain.user.service.ProfileImageStorage;
import com.kitschcatch.backend.global.security.JwtTokenProvider;
import java.util.Map;
import java.util.HashMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
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
	"app.orders.expiration-enabled=false"
})
class UserProfileHttpTest {

	@LocalServerPort
	private int port;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private JwtTokenProvider jwtTokenProvider;

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
