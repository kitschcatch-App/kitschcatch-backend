package com.kitschcatch.backend.global.security;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest(properties = "kakao.oauth.native-app-key=test-native-app-key")
class SecurityConfigTest {

	private MockMvc mockMvc;

	@Autowired
	void setUp(WebApplicationContext context) {
		this.mockMvc = MockMvcBuilders.webAppContextSetup(context)
			.apply(springSecurity())
			.build();
	}

	@Test
	@DisplayName("인증 API가 아닌 요청은 인증 없이 접근할 수 없다")
	void nonAuthRequestRequiresAuthentication() throws Exception {
		mockMvc.perform(get("/api/protected"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.error.code").value("AUTH_004"));
	}

	@Test
	@DisplayName("허용된 인증 API는 잘못된 Bearer 토큰이 있어도 요청 처리를 계속한다")
	void permitAllAuthRequestIgnoresInvalidBearerToken() throws Exception {
		mockMvc.perform(post("/api/auth/kakao/nonce")
				.header("Authorization", "Bearer invalid-token"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.nonce").isNotEmpty());
	}

	@Test
	@DisplayName("보호 API의 잘못된 Bearer 토큰은 공통 에러 형식으로 반환한다")
	void protectedRequestWithInvalidBearerTokenReturnsCommonError() throws Exception {
		mockMvc.perform(get("/api/protected")
				.header("Authorization", "Bearer invalid-token"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.error.code").value("AUTH_004"));
	}
}
