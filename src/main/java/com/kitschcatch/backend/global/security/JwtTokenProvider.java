package com.kitschcatch.backend.global.security;

import com.kitschcatch.backend.global.exception.BusinessException;
import com.kitschcatch.backend.global.exception.ErrorCode;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

public class JwtTokenProvider {

	private static final String TOKEN_TYPE_CLAIM = "type";
	private static final String ACCESS_TOKEN_TYPE = "access";
	private static final String REFRESH_TOKEN_TYPE = "refresh";
	private static final String ISSUER = "kitschcatch";
	private static final int MINIMUM_SECRET_BYTES = 32;

	private final JwtProperties properties;
	private final JwtEncoder jwtEncoder;
	private final JwtDecoder jwtDecoder;

	public JwtTokenProvider(JwtProperties properties) {
		this.properties = properties;
		byte[] secretBytes = properties.secret().getBytes(StandardCharsets.UTF_8);
		if (secretBytes.length < MINIMUM_SECRET_BYTES) {
			throw new IllegalArgumentException("JWT secret must be at least 32 bytes.");
		}
		SecretKey secretKey = new SecretKeySpec(secretBytes, "HmacSHA256");
		this.jwtEncoder = new NimbusJwtEncoder(new ImmutableSecret<>(secretKey));
		this.jwtDecoder = NimbusJwtDecoder.withSecretKey(secretKey)
			.macAlgorithm(MacAlgorithm.HS256)
			.build();
	}

	public String createAccessToken(Long userId) {
		return createToken(userId, ACCESS_TOKEN_TYPE, properties.accessTokenTtl());
	}

	public String createRefreshToken(Long userId) {
		return createToken(userId, REFRESH_TOKEN_TYPE, properties.refreshTokenTtl());
	}

	public AuthenticatedUser parseAccessToken(String token) {
		Jwt jwt = decode(token, ErrorCode.INVALID_AUTH_TOKEN);
		if (!ACCESS_TOKEN_TYPE.equals(jwt.getClaimAsString(TOKEN_TYPE_CLAIM))) {
			throw new BusinessException(ErrorCode.INVALID_AUTH_TOKEN);
		}
		return new AuthenticatedUser(parseSubject(jwt, ErrorCode.INVALID_AUTH_TOKEN));
	}

	public RefreshTokenClaims parseRefreshToken(String token) {
		Jwt jwt = decode(token, ErrorCode.INVALID_REFRESH_TOKEN);
		if (!REFRESH_TOKEN_TYPE.equals(jwt.getClaimAsString(TOKEN_TYPE_CLAIM))) {
			throw new BusinessException(ErrorCode.INVALID_REFRESH_TOKEN);
		}
		return new RefreshTokenClaims(parseSubject(jwt, ErrorCode.INVALID_REFRESH_TOKEN));
	}

	public String hashToken(String token) {
		return hash(token);
	}

	public static String hash(String token) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			return HexFormat.of().formatHex(digest.digest(token.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is not available.", exception);
		}
	}

	public Duration getAccessTokenTtl() {
		return properties.accessTokenTtl();
	}

	public Duration getRefreshTokenTtl() {
		return properties.refreshTokenTtl();
	}

	private String createToken(Long userId, String tokenType, Duration ttl) {
		Instant now = Instant.now();
		JwtClaimsSet claims = JwtClaimsSet.builder()
			.issuer(ISSUER)
			.issuedAt(now)
			.expiresAt(now.plus(ttl))
			.id(UUID.randomUUID().toString())
			.subject(String.valueOf(userId))
			.claim(TOKEN_TYPE_CLAIM, tokenType)
			.build();
		JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
		return jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
	}

	private Jwt decode(String token, ErrorCode errorCode) {
		try {
			return jwtDecoder.decode(token);
		} catch (JwtException exception) {
			throw new BusinessException(errorCode);
		}
	}

	private Long parseSubject(Jwt jwt, ErrorCode errorCode) {
		try {
			return Long.valueOf(jwt.getSubject());
		} catch (NumberFormatException exception) {
			throw new BusinessException(errorCode);
		}
	}
}
