package com.kitschcatch.backend.domain.auth.service;

import com.kitschcatch.backend.domain.auth.oidc.KakaoOidcUser;
import com.kitschcatch.backend.domain.user.entity.AuthProvider;
import com.kitschcatch.backend.domain.user.entity.User;
import com.kitschcatch.backend.domain.user.repository.UserRepository;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class KakaoUserService {

	private static final String DEFAULT_NICKNAME_PREFIX = "kakao-";

	private final UserRepository userRepository;

	public KakaoUserService(UserRepository userRepository) {
		this.userRepository = userRepository;
	}

	@Transactional(readOnly = true)
	public Optional<User> findKakaoUser(String providerUserId) {
		return userRepository.findByAuthProviderAndProviderUserId(AuthProvider.KAKAO, providerUserId);
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public User createKakaoUser(KakaoOidcUser kakaoUser) {
		return userRepository.saveAndFlush(User.builder()
			.nickname(resolveNickname(kakaoUser))
			.email(kakaoUser.email())
			.authProvider(AuthProvider.KAKAO)
			.providerUserId(kakaoUser.subject())
			.build());
	}

	private String resolveNickname(KakaoOidcUser kakaoUser) {
		if (StringUtils.hasText(kakaoUser.nickname())) {
			return kakaoUser.nickname();
		}
		return DEFAULT_NICKNAME_PREFIX + kakaoUser.subject();
	}
}
