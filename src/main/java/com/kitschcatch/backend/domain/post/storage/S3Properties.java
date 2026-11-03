package com.kitschcatch.backend.domain.post.storage;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

@ConfigurationProperties(prefix = "app.s3")
public record S3Properties(
	String region,
	String bucket,
	String publicBaseUrl,
	Duration uploadUrlTtl,
	Duration downloadUrlTtl,
	String postImagePrefix,
	String chatImagePrefix
) {

	public S3Properties {
		region = StringUtils.hasText(region) ? region : "ap-northeast-2";
		publicBaseUrl = trimTrailingSlash(publicBaseUrl);
		uploadUrlTtl = uploadUrlTtl != null ? uploadUrlTtl : Duration.ofMinutes(5);
		downloadUrlTtl = downloadUrlTtl != null ? downloadUrlTtl : Duration.ofMinutes(15);
		if (downloadUrlTtl.compareTo(Duration.ofSeconds(1)) < 0
			|| downloadUrlTtl.compareTo(Duration.ofDays(7)) > 0 || downloadUrlTtl.getNano() != 0) {
			throw new IllegalArgumentException("이미지 조회 URL 유효기간은 1초 이상 7일 이하의 정수 초여야 합니다.");
		}
		postImagePrefix = StringUtils.hasText(postImagePrefix) ? postImagePrefix : "posts";
		chatImagePrefix = StringUtils.hasText(chatImagePrefix) ? chatImagePrefix : "chats";
	}

	private static String trimTrailingSlash(String value) {
		if (!StringUtils.hasText(value)) {
			return "";
		}
		String trimmed = value.trim();
		while (trimmed.endsWith("/")) {
			trimmed = trimmed.substring(0, trimmed.length() - 1);
		}
		return trimmed;
	}
}
