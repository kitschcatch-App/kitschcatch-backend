// Apple 공개 키와 서버 nonce로 모바일 ID 토큰을 검증한다.
package com.kitschcatch.backend.domain.auth.oidc;

import com.kitschcatch.backend.domain.auth.client.SocialIdentity;
import com.kitschcatch.backend.domain.auth.client.SocialOAuthProperties;
import com.kitschcatch.backend.domain.user.entity.AuthProvider;
import com.kitschcatch.backend.global.exception.BusinessException;
import com.kitschcatch.backend.global.exception.ErrorCode;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;

@Component
public class AppleOidcTokenVerifier {
	private static final String ISSUER = "https://appleid.apple.com";
	private final String clientId;
	private final JwtDecoder decoder;

	public AppleOidcTokenVerifier(SocialOAuthProperties properties) {
		this.clientId = properties.apple().clientId();
		var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build());
		factory.setReadTimeout(Duration.ofSeconds(5));
		NimbusJwtDecoder jwtDecoder = NimbusJwtDecoder.withJwkSetUri(properties.apple().jwkSetUri())
			.jwsAlgorithm(SignatureAlgorithm.RS256).restOperations(new RestTemplate(factory)).build();
		jwtDecoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(ISSUER));
		this.decoder = jwtDecoder;
	}

	public SocialIdentity verify(String idToken, String nonce) {
		if (!StringUtils.hasText(clientId)) throw new BusinessException(ErrorCode.SOCIAL_LOGIN_NOT_CONFIGURED);
		try {
			var jwt = decoder.decode(idToken);
			Instant now = Instant.now();
			if (!jwt.getAudience().equals(java.util.List.of(clientId))
				|| !nonce.equals(jwt.getClaimAsString("nonce"))
				|| jwt.getExpiresAt() == null || !jwt.getExpiresAt().isAfter(now)
				|| jwt.getIssuedAt() == null || jwt.getIssuedAt().isAfter(now)
				|| (jwt.getNotBefore() != null && jwt.getNotBefore().isAfter(now))) throw failed();
			String email = "true".equals(String.valueOf(jwt.getClaims().get("email_verified")))
				? jwt.getClaimAsString("email") : null;
			return new SocialIdentity(AuthProvider.APPLE, jwt.getSubject(), email);
		} catch (JwtException | IllegalArgumentException exception) {
			throw failed();
		}
	}
	private BusinessException failed() { return new BusinessException(ErrorCode.APPLE_LOGIN_FAILED); }
}
