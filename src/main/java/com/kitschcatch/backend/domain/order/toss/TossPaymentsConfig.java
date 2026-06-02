// 토스페이먼츠 설정 프로퍼티를 Spring Bean으로 등록하는 구성
package com.kitschcatch.backend.domain.order.toss;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
@EnableConfigurationProperties(TossPaymentsProperties.class)
public class TossPaymentsConfig {

	@Bean
	public RestClient.Builder restClientBuilder() {
		return RestClient.builder();
	}
}
