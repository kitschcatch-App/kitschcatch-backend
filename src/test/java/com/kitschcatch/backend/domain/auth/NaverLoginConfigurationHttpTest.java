// 네이버 미설정 API가 503을 반환하며 로그인 state를 저장하거나 소비하지 않는지 검증한다.
package com.kitschcatch.backend.domain.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.kitschcatch.backend.domain.auth.entity.LoginNonce;
import com.kitschcatch.backend.domain.auth.repository.LoginNonceRepository;
import com.kitschcatch.backend.global.security.JwtTokenProvider;
import java.time.LocalDateTime;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.web.client.RestClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
	"spring.datasource.url=jdbc:h2:mem:naver-config;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
	"spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa",
	"spring.datasource.password=", "spring.jpa.hibernate.ddl-auto=create-drop",
	"kakao.oauth.native-app-key=naver-config-kakao",
	"app.orders.expiration-enabled=false", "app.payments.recovery.enabled=false",
	"social.oauth.naver.client-id=", "social.oauth.naver.client-secret=", "social.oauth.naver.redirect-uri="
})
@DirtiesContext
class NaverLoginConfigurationHttpTest {
	@LocalServerPort private int port;
	@Autowired private LoginNonceRepository nonces;

	@Test
	void unconfiguredProviderReturns503WithoutSavingOrConsumingChallenge() {
		String state = "a".repeat(43);
		var existing = nonces.saveAndFlush(LoginNonce.builder()
			.nonceHash(JwtTokenProvider.hash("NAVER:" + state))
			.expiresAt(LocalDateTime.now().plusMinutes(5)).build());
		long before = nonces.count();

		assertUnavailable("/api/auth/naver/state", null);
		assertUnavailable("/api/auth/naver/mobile-login", Map.of("authorizationCode", "code", "state", state));

		assertThat(nonces.count()).isEqualTo(before);
		assertThat(nonces.findById(existing.getId()).orElseThrow().isConsumed()).isFalse();
	}

	@Test
	void callbackIsPublicAndDoesNotReflectCredentialsOrLoadExternalResources() {
		RestClient.create("http://127.0.0.1:" + port).get()
			.uri("/api/auth/naver/callback?code=secret-code&state=secret-state&error_description=secret-error")
			.exchange((request, response) -> {
				assertThat(response.getStatusCode().value()).isEqualTo(200);
				assertThat(response.getHeaders().getContentType().toString()).contains("text/html", "UTF-8");
				assertThat(response.getHeaders().getCacheControl()).contains("no-store");
				assertThat(response.getHeaders().getFirst("Referrer-Policy")).isEqualTo("no-referrer");
				assertThat(response.getHeaders().getFirst("Content-Security-Policy")).contains("default-src 'none'");
				assertThat(response.bodyTo(String.class)).contains("키치캐치 앱으로 돌아가 주세요")
					.doesNotContain("secret-code", "secret-state", "secret-error", "<script", "<iframe");
				return null;
			});
	}

	private void assertUnavailable(String path, Map<String, String> body) {
		var request = RestClient.create("http://127.0.0.1:" + port).post().uri(path)
			.contentType(MediaType.APPLICATION_JSON);
		if (body != null) request.body(body);
		request.exchange((req, response) -> {
			assertThat(response.getStatusCode().value()).isEqualTo(503);
			Map<?, ?> result = response.bodyTo(Map.class);
			assertThat(result.get("success")).isEqualTo(false);
			assertThat(((Map<?, ?>) result.get("error")).get("code")).isEqualTo("AUTH_007");
			return null;
		});
	}
}
