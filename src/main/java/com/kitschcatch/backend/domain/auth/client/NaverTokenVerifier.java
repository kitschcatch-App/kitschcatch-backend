// 등록된 네이버 앱의 인증 코드를 교환하고 서버에서 사용자 정보를 검증한다.
package com.kitschcatch.backend.domain.auth.client;

import com.kitschcatch.backend.domain.user.entity.AuthProvider;
import com.kitschcatch.backend.global.exception.BusinessException;
import com.kitschcatch.backend.global.exception.ErrorCode;
import java.net.http.HttpClient;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;

@Component
public class NaverTokenVerifier {
	private static final Logger LOGGER = LoggerFactory.getLogger(NaverTokenVerifier.class);
	private final SocialOAuthProperties.Naver properties;
	private final RestClient client;

	public NaverTokenVerifier(SocialOAuthProperties properties) {
		this.properties = properties.naver();
		var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build());
		factory.setReadTimeout(Duration.ofSeconds(5));
		this.client = RestClient.builder().requestFactory(factory).build();
	}

	public String authorizationUrl(String state) {
		validateConfiguration();
		return UriComponentsBuilder.fromUriString(properties.authorizeUri())
			.queryParam("response_type", "code").queryParam("client_id", properties.clientId())
			.queryParam("redirect_uri", properties.redirectUri()).queryParam("state", state)
			.build().encode().toUriString();
	}

	public SocialIdentity verify(String code, String state) {
		validateConfiguration();
		var form = new LinkedMultiValueMap<String, String>();
		form.add("grant_type", "authorization_code");
		form.add("client_id", properties.clientId());
		form.add("client_secret", properties.clientSecret());
		form.add("code", code);
		form.add("state", state);
		String stage = "token_exchange";
		try {
			Token token = client.post().uri(properties.tokenUri()).contentType(MediaType.APPLICATION_FORM_URLENCODED)
				.body(form).retrieve().body(Token.class);
			if (token == null || token.error() != null || !StringUtils.hasText(token.access_token())
				|| !"bearer".equalsIgnoreCase(token.token_type()) || token.expires_in() == null
				|| Long.parseLong(token.expires_in()) <= 0) throw failed(stage);
			stage = "profile_lookup";
			Profile profile = client.get().uri(properties.userInfoUri())
				.headers(headers -> headers.setBearerAuth(token.access_token())).retrieve().body(Profile.class);
			if (profile == null || !"00".equals(profile.resultcode()) || profile.response() == null) throw failed(stage);
			return new SocialIdentity(AuthProvider.NAVER, profile.response().id(), profile.response().email());
		} catch (RestClientException | IllegalArgumentException exception) {
			throw failed(stage);
		}
	}

	public void validateConfiguration() {
		if (!StringUtils.hasText(properties.clientId()) || !StringUtils.hasText(properties.clientSecret())
			|| !StringUtils.hasText(properties.redirectUri())) {
			throw new BusinessException(ErrorCode.SOCIAL_LOGIN_NOT_CONFIGURED);
		}
	}
	private BusinessException failed(String stage) {
		LOGGER.warn("Naver login rejected at stage={}", stage);
		return new BusinessException(ErrorCode.NAVER_LOGIN_FAILED);
	}
	public record Token(String access_token, String token_type, String expires_in, String error) {}
	public record Profile(String resultcode, Member response) {}
	public record Member(String id, String email) {}
}
