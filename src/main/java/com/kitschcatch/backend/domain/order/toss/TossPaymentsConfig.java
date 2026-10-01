// 토스페이먼츠 설정 프로퍼티를 Spring Bean으로 등록하는 구성
package com.kitschcatch.backend.domain.order.toss;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;

@Configuration
@EnableConfigurationProperties(TossPaymentsProperties.class)
public class TossPaymentsConfig {

	@Bean
	public RestClient.Builder restClientBuilder(
		@Value("${app.toss-payments.connect-timeout:3s}") Duration connectTimeout,
		@Value("${app.toss-payments.read-timeout:10s}") Duration readTimeout
	) {
		if (connectTimeout.isNegative() || connectTimeout.isZero() || readTimeout.isNegative() || readTimeout.isZero()) {
			throw new IllegalArgumentException("PG 타임아웃은 양수여야 합니다.");
		}
		SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
		factory.setConnectTimeout(connectTimeout);
		factory.setReadTimeout(readTimeout);
		return RestClient.builder().requestFactory(factory);
	}
}
