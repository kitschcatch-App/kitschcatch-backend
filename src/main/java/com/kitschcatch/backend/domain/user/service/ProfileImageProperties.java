// 프로필 이미지의 최대 바이트 크기와 업로드 URL 유효기간을 설정한다.
package com.kitschcatch.backend.domain.user.service;

import jakarta.validation.constraints.Min;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "app.profile-image")
public record ProfileImageProperties(
	@DefaultValue("5000000") @Min(1) long maxSizeBytes,
	@DefaultValue("10m") Duration uploadUrlTtl
) {
	public ProfileImageProperties {
		if (maxSizeBytes < 1 || uploadUrlTtl == null || uploadUrlTtl.compareTo(Duration.ofSeconds(1)) < 0
			|| uploadUrlTtl.compareTo(Duration.ofDays(7)) > 0 || uploadUrlTtl.getNano() != 0) {
			throw new IllegalArgumentException("프로필 이미지 크기는 양수, URL 유효기간은 1초 이상 7일 이하의 정수 초여야 합니다.");
		}
	}
}
