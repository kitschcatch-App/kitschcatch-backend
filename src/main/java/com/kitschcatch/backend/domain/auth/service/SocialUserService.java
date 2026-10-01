// 이메일 연결 없이 제공자 식별자로 사용자를 생성하고 조회한다.
package com.kitschcatch.backend.domain.auth.service;

import com.kitschcatch.backend.domain.auth.client.SocialIdentity;
import com.kitschcatch.backend.domain.user.entity.User;
import com.kitschcatch.backend.domain.user.repository.UserRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SocialUserService {
	private final UserRepository users;
	public SocialUserService(UserRepository users) { this.users = users; }

	@Transactional(readOnly = true)
	public Optional<User> find(SocialIdentity identity) {
		return users.findByAuthProviderAndProviderUserId(identity.provider(), identity.subject());
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public User create(SocialIdentity identity) {
		return users.saveAndFlush(User.builder().authProvider(identity.provider()).providerUserId(identity.subject())
			.email(identity.email()).nickname(identity.provider().name().toLowerCase(java.util.Locale.ROOT)
				+ "-" + UUID.randomUUID().toString().substring(0, 12)).build());
	}
}
