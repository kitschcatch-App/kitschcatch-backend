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
	String postImagePrefix,
	String chatImagePrefix
) {

	public S3Properties {
		region = StringUtils.hasText(region) ? region : "ap-northeast-2";
		publicBaseUrl = trimTrailingSlash(publicBaseUrl);
		uploadUrlTtl = uploadUrlTtl != null ? uploadUrlTtl : Duration.ofMinutes(5);
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
