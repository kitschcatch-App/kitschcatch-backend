package com.kitschcatch.backend.domain.payment.client;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

@ConfigurationProperties(prefix = "app.toss")
public record TossPaymentProperties(
	String secretKey,
	String clientKey,
	String baseUrl,
	String successUrl,
	String failUrl,
	Duration connectTimeout,
	Duration readTimeout
) {

	public TossPaymentProperties {
		clientKey = StringUtils.hasText(clientKey) ? clientKey.trim() : "";
		baseUrl = StringUtils.hasText(baseUrl) ? trimTrailingSlash(baseUrl) : "https://api.tosspayments.com";
		successUrl = StringUtils.hasText(successUrl) ? successUrl.trim() : "";
		failUrl = StringUtils.hasText(failUrl) ? failUrl.trim() : "";
		connectTimeout = connectTimeout != null ? connectTimeout : Duration.ofSeconds(3);
		readTimeout = readTimeout != null ? readTimeout : Duration.ofSeconds(10);
	}

	public boolean hasCheckoutValues() {
		return StringUtils.hasText(clientKey) && StringUtils.hasText(successUrl) && StringUtils.hasText(failUrl);
	}

	public boolean hasSecretKey() {
		return StringUtils.hasText(secretKey);
	}

	private static String trimTrailingSlash(String value) {
		String trimmed = value.trim();
		while (trimmed.endsWith("/")) {
			trimmed = trimmed.substring(0, trimmed.length() - 1);
		}
		return trimmed;
	}
}
