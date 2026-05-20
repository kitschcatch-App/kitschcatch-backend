package com.kitschcatch.backend.domain.auth.service;

import com.kitschcatch.backend.domain.auth.dto.AuthTokenResponse;
import com.kitschcatch.backend.domain.auth.dto.AuthUserResponse;
import com.kitschcatch.backend.domain.auth.dto.KakaoNonceResponse;
import com.kitschcatch.backend.domain.auth.entity.LoginNonce;
import com.kitschcatch.backend.domain.auth.entity.RefreshToken;
import com.kitschcatch.backend.domain.auth.oidc.KakaoOidcTokenVerifier;
import com.kitschcatch.backend.domain.auth.oidc.KakaoOidcUser;
import com.kitschcatch.backend.domain.auth.repository.LoginNonceRepository;
import com.kitschcatch.backend.domain.auth.repository.RefreshTokenRepository;
import com.kitschcatch.backend.domain.user.entity.User;
import com.kitschcatch.backend.global.exception.BusinessException;
import com.kitschcatch.backend.global.exception.ErrorCode;
import com.kitschcatch.backend.global.security.JwtTokenProvider;
import com.kitschcatch.backend.global.security.RefreshTokenClaims;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Base64;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@Transactional
public class AuthService {

	private static final Duration LOGIN_NONCE_TTL = Duration.ofMinutes(5);
	private static final int NONCE_BYTES = 32;
	private static final SecureRandom SECURE_RANDOM = new SecureRandom();

	private final KakaoOidcTokenVerifier kakaoOidcTokenVerifier;
	private final KakaoUserService kakaoUserService;
	private final RefreshTokenRepository refreshTokenRepository;
	private final LoginNonceRepository loginNonceRepository;
	private final JwtTokenProvider jwtTokenProvider;

	public AuthService(
		KakaoOidcTokenVerifier kakaoOidcTokenVerifier,
		KakaoUserService kakaoUserService,
		RefreshTokenRepository refreshTokenRepository,
		LoginNonceRepository loginNonceRepository,
		JwtTokenProvider jwtTokenProvider
	) {
		this.kakaoOidcTokenVerifier = kakaoOidcTokenVerifier;
		this.kakaoUserService = kakaoUserService;
		this.refreshTokenRepository = refreshTokenRepository;
		this.loginNonceRepository = loginNonceRepository;
		this.jwtTokenProvider = jwtTokenProvider;
	}

	public KakaoNonceResponse createKakaoLoginNonce() {
		String nonce = generateNonce();
		loginNonceRepository.save(LoginNonce.builder()
			.nonceHash(jwtTokenProvider.hashToken(nonce))
			.expiresAt(LocalDateTime.now().plus(LOGIN_NONCE_TTL))
			.build());
		return new KakaoNonceResponse(nonce, LOGIN_NONCE_TTL.toSeconds());
	}

	public AuthTokenResponse loginWithKakaoIdToken(String idToken, String nonce) {
		KakaoOidcUser kakaoUser = kakaoOidcTokenVerifier.verify(idToken, nonce);
		if (!loginNonceRepository.consumeByRawNonce(nonce)) {
			throw new BusinessException(ErrorCode.KAKAO_LOGIN_FAILED);
		}
		return loginWithKakaoUser(kakaoUser);
	}

	private AuthTokenResponse loginWithKakaoUser(KakaoOidcUser kakaoUser) {
		if (!StringUtils.hasText(kakaoUser.email())) {
			throw new BusinessException(ErrorCode.KAKAO_EMAIL_REQUIRED);
		}

		User user = kakaoUserService.findKakaoUser(kakaoUser.subject())
			.orElseGet(() -> createKakaoUser(kakaoUser));

		return issueTokenResponse(user);
	}

	public AuthTokenResponse refresh(String refreshToken) {
		RefreshTokenClaims claims = jwtTokenProvider.parseRefreshToken(refreshToken);
		RefreshToken savedToken = refreshTokenRepository.findByTokenHashForUpdate(jwtTokenProvider.hashToken(refreshToken))
			.filter(token -> token.isActive(LocalDateTime.now()))
			.filter(token -> token.getUser().getId().equals(claims.userId()))
			.orElseThrow(() -> new BusinessException(ErrorCode.INVALID_REFRESH_TOKEN));

		savedToken.revoke();
		return issueTokenResponse(savedToken.getUser());
	}

	public void logout(String refreshToken) {
		RefreshTokenClaims claims = jwtTokenProvider.parseRefreshToken(refreshToken);
		RefreshToken savedToken = refreshTokenRepository.findByTokenHashForUpdate(jwtTokenProvider.hashToken(refreshToken))
			.filter(token -> token.getUser().getId().equals(claims.userId()))
			.orElseThrow(() -> new BusinessException(ErrorCode.INVALID_REFRESH_TOKEN));
		savedToken.revoke();
	}

	private AuthTokenResponse issueTokenResponse(User user) {
		LocalDateTime now = LocalDateTime.now();
		String accessToken = jwtTokenProvider.createAccessToken(user.getId());
		String refreshToken = jwtTokenProvider.createRefreshToken(user.getId());
		refreshTokenRepository.deleteInactiveByUser(user, now);
		refreshTokenRepository.save(RefreshToken.builder()
			.user(user)
			.tokenHash(jwtTokenProvider.hashToken(refreshToken))
			.expiresAt(now.plus(jwtTokenProvider.getRefreshTokenTtl()))
			.build());

		return new AuthTokenResponse(
			"Bearer",
			accessToken,
			jwtTokenProvider.getAccessTokenTtl().toSeconds(),
			refreshToken,
			jwtTokenProvider.getRefreshTokenTtl().toSeconds(),
			new AuthUserResponse(user.getId(), user.getEmail(), user.getNickname())
		);
	}

	private User createKakaoUser(KakaoOidcUser kakaoUser) {
		try {
			return kakaoUserService.createKakaoUser(kakaoUser);
		} catch (DataIntegrityViolationException exception) {
			return kakaoUserService.findKakaoUser(kakaoUser.subject())
				.orElseThrow(() -> exception);
		}
	}

	private String generateNonce() {
		byte[] bytes = new byte[NONCE_BYTES];
		SECURE_RANDOM.nextBytes(bytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}
}
