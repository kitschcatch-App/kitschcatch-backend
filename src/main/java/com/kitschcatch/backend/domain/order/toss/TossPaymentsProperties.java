// 토스페이먼츠 API 호출에 필요한 설정값을 보관하는 프로퍼티
package com.kitschcatch.backend.domain.order.toss;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

@ConfigurationProperties(prefix = "app.toss-payments")
public record TossPaymentsProperties(
	String baseUrl,
	String secretKey
) {

	public TossPaymentsProperties {
		baseUrl = StringUtils.hasText(baseUrl) ? trimTrailingSlash(baseUrl) : "https://api.tosspayments.com";
		secretKey = StringUtils.hasText(secretKey) ? secretKey.trim() : "";
	}

	public String authorizationHeader() {
		if (!StringUtils.hasText(secretKey)) {
			throw new IllegalStateException("Toss Payments secret key is not configured.");
		}
		String token = Base64.getEncoder()
			.encodeToString((secretKey + ":").getBytes(StandardCharsets.UTF_8));
		return "Basic " + token;
	}

	private static String trimTrailingSlash(String value) {
		String trimmed = value.trim();
		while (trimmed.endsWith("/")) {
			trimmed = trimmed.substring(0, trimmed.length() - 1);
		}
		return trimmed;
	}
}
