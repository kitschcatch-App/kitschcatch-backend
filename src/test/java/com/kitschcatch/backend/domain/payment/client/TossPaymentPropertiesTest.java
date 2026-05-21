package com.kitschcatch.backend.domain.payment.client;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TossPaymentPropertiesTest {

	@Test
	@DisplayName("토스 결제 HTTP 타임아웃 기본값을 사용한다")
	void usesDefaultTimeouts() {
		TossPaymentProperties properties = new TossPaymentProperties(
			"test_sk",
			"test_ck",
			"",
			"https://example.com/success",
			"https://example.com/fail",
			null,
			null
		);

		assertThat(properties.connectTimeout()).isEqualTo(Duration.ofSeconds(3));
		assertThat(properties.readTimeout()).isEqualTo(Duration.ofSeconds(10));
	}
}
