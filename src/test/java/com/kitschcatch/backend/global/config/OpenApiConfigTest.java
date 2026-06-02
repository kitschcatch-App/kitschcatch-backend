package com.kitschcatch.backend.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.swagger.v3.oas.models.OpenAPI;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class OpenApiConfigTest {

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withUserConfiguration(OpenApiConfig.class);

	@Test
	@DisplayName("OpenAPI 설정은 springdoc API 문서가 비활성화되면 로드되지 않는다")
	void openApiBeanIsNotLoadedWhenApiDocsDisabled() {
		contextRunner
			.withPropertyValues("springdoc.api-docs.enabled=false")
			.run(context -> assertThat(context).doesNotHaveBean(OpenAPI.class));
	}

	@Test
	@DisplayName("OpenAPI 설정은 springdoc API 문서가 활성화되면 로드된다")
	void openApiBeanIsLoadedWhenApiDocsEnabled() {
		contextRunner
			.withPropertyValues("springdoc.api-docs.enabled=true")
			.run(context -> assertThat(context).hasSingleBean(OpenAPI.class));
	}
}
