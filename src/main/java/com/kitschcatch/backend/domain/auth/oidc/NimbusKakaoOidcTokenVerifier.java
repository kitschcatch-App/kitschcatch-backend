package com.kitschcatch.backend.domain.auth.oidc;

import com.kitschcatch.backend.domain.auth.client.KakaoOAuthProperties;
import com.kitschcatch.backend.global.exception.BusinessException;
import com.kitschcatch.backend.global.exception.ErrorCode;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Component;

@Component
public class NimbusKakaoOidcTokenVerifier implements KakaoOidcTokenVerifier {

	private final JwtDecoder jwtDecoder;

	public NimbusKakaoOidcTokenVerifier(KakaoOAuthProperties properties) {
		NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(properties.jwkSetUri()).build();
		OAuth2TokenValidator<Jwt> issuerValidator = JwtValidators.createDefaultWithIssuer(properties.issuerUri());
		OAuth2TokenValidator<Jwt> audienceValidator = KakaoOidcAudienceValidator.from(properties);
		decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(issuerValidator, audienceValidator));
		this.jwtDecoder = decoder;
	}

	@Override
	public KakaoOidcUser verify(String idToken, String nonce) {
		try {
			Jwt jwt = jwtDecoder.decode(idToken);
			if (!nonce.equals(jwt.getClaimAsString("nonce"))) {
				throw new BusinessException(ErrorCode.KAKAO_LOGIN_FAILED);
			}
			return new KakaoOidcUser(
				jwt.getSubject(),
				jwt.getClaimAsString("email"),
				jwt.getClaimAsString("nickname")
			);
		} catch (JwtException exception) {
			throw new BusinessException(ErrorCode.KAKAO_LOGIN_FAILED);
		}
	}
}
