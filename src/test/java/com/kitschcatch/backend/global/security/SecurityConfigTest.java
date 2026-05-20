package com.kitschcatch.backend.global.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

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
			.andExpect(status().isForbidden());
	}
}
