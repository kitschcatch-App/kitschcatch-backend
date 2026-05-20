package com.kitschcatch.backend.domain.auth.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

class KakaoUserServiceTest {

	@Test
	@DisplayName("카카오 사용자 생성은 중복 충돌 롤백을 격리하기 위해 별도 트랜잭션을 사용한다")
	void createKakaoUserUsesRequiresNewTransaction() throws Exception {
		Method method = KakaoUserService.class.getMethod(
			"createKakaoUser",
			com.kitschcatch.backend.domain.auth.oidc.KakaoOidcUser.class
		);

		Transactional transactional = method.getAnnotation(Transactional.class);

		assertThat(transactional).isNotNull();
		assertThat(transactional.propagation()).isEqualTo(Propagation.REQUIRES_NEW);
	}
}
