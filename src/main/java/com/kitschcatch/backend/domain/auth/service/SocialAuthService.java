// 제공자별 일회용 로그인 검증과 기존 서비스 토큰 발급을 연결한다.
package com.kitschcatch.backend.domain.auth.service;

import com.kitschcatch.backend.domain.auth.client.NaverTokenVerifier;
import com.kitschcatch.backend.domain.auth.client.SocialIdentity;
import com.kitschcatch.backend.domain.auth.dto.AuthTokenResponse;
import com.kitschcatch.backend.domain.auth.dto.KakaoNonceResponse;
import com.kitschcatch.backend.domain.auth.dto.NaverAuthorizationResponse;
import com.kitschcatch.backend.domain.auth.entity.LoginNonce;
import com.kitschcatch.backend.domain.auth.oidc.AppleOidcTokenVerifier;
import com.kitschcatch.backend.domain.auth.repository.LoginNonceRepository;
import com.kitschcatch.backend.domain.user.entity.AuthProvider;
import com.kitschcatch.backend.domain.user.entity.User;
import com.kitschcatch.backend.global.exception.BusinessException;
import com.kitschcatch.backend.global.exception.ErrorCode;
import com.kitschcatch.backend.global.security.JwtTokenProvider;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class SocialAuthService {
	private static final SecureRandom RANDOM = new SecureRandom();
	private static final long TTL_SECONDS = 300;
	private final NaverTokenVerifier naver;
	private final AppleOidcTokenVerifier apple;
	private final LoginNonceRepository nonces;
	private final SocialUserService users;
	private final AuthService auth;

	public SocialAuthService(NaverTokenVerifier naver, AppleOidcTokenVerifier apple,
		LoginNonceRepository nonces, SocialUserService users, AuthService auth) {
		this.naver = naver; this.apple = apple; this.nonces = nonces; this.users = users; this.auth = auth;
	}

	public KakaoNonceResponse appleNonce() {
		return new KakaoNonceResponse(challenge(AuthProvider.APPLE), TTL_SECONDS);
	}
	public NaverAuthorizationResponse naverAuthorization() {
		String state = challenge(AuthProvider.NAVER);
		return new NaverAuthorizationResponse(state, naver.authorizationUrl(state), TTL_SECONDS);
	}
	public AuthTokenResponse appleLogin(String idToken, String nonce) {
		SocialIdentity identity = apple.verify(idToken, nonce);
		consume(AuthProvider.APPLE, nonce, ErrorCode.APPLE_LOGIN_FAILED);
		return login(identity);
	}
	public AuthTokenResponse naverLogin(String code, String state) {
		consume(AuthProvider.NAVER, state, ErrorCode.NAVER_LOGIN_FAILED);
		return login(naver.verify(code, state));
	}
	private AuthTokenResponse login(SocialIdentity identity) {
		User user = users.find(identity).orElseGet(() -> create(identity));
		return auth.issueTokenResponse(user);
	}
	private User create(SocialIdentity identity) {
		try { return users.create(identity); }
		catch (DataIntegrityViolationException exception) {
			// 생성 트랜잭션만 롤백하고 같은 제공자 ID의 경합 승자를 조회한다.
			return users.find(identity).orElseThrow(() -> exception);
		}
	}
	private String challenge(AuthProvider provider) {
		byte[] bytes = new byte[32]; RANDOM.nextBytes(bytes);
		String value = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
		nonces.save(LoginNonce.builder().nonceHash(JwtTokenProvider.hash(bound(provider, value)))
			.expiresAt(LocalDateTime.now().plusSeconds(TTL_SECONDS)).build());
		return value;
	}
	private void consume(AuthProvider provider, String value, ErrorCode error) {
		if (!nonces.consumeByRawNonce(bound(provider, value))) throw new BusinessException(error);
	}
	private String bound(AuthProvider provider, String value) { return provider.name() + ":" + value; }
}
