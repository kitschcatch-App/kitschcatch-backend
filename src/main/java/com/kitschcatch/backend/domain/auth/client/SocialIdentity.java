// 검증된 제공자 사용자 ID와 선택 이메일을 전달한다.
package com.kitschcatch.backend.domain.auth.client;

import com.kitschcatch.backend.domain.user.entity.AuthProvider;
import org.springframework.util.StringUtils;

public record SocialIdentity(AuthProvider provider, String subject, String email) {
	public SocialIdentity {
		if (provider == null || !StringUtils.hasText(subject) || subject.length() > 100) {
			throw new IllegalArgumentException("Invalid social identity");
		}
		// 이메일은 연락처 정보이며 계정 연결에 사용하지 않는다.
		if (!StringUtils.hasText(email) || email.length() > 255) email = null;
	}
}
