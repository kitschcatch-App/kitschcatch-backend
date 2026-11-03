// 이미지 조회 URL 유효기간의 기본값과 AWS 서명 제한을 검증한다.
package com.kitschcatch.backend.domain.post.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class S3PropertiesTest {
	@Test
	void defaultsDownloadTtlToFifteenMinutesWithoutChangingUploadTtl() {
		var properties = properties(null);
		assertThat(properties.downloadUrlTtl()).isEqualTo(Duration.ofMinutes(15));
		assertThat(properties.uploadUrlTtl()).isEqualTo(Duration.ofMinutes(5));
	}

	@ParameterizedTest
	@ValueSource(strings = {"PT1S", "PT15M", "P7D"})
	void acceptsWholeSecondsWithinSigningLimit(String value) {
		assertThat(properties(Duration.parse(value)).downloadUrlTtl()).isEqualTo(Duration.parse(value));
	}

	@ParameterizedTest
	@ValueSource(strings = {"PT0S", "PT-1S", "PT0.5S", "PT1.5S", "P7DT1S"})
	void rejectsInvalidDownloadTtlAtConfigurationTime(String value) {
		assertThatThrownBy(() -> properties(Duration.parse(value))).isInstanceOf(IllegalArgumentException.class);
	}

	private S3Properties properties(Duration downloadTtl) {
		return new S3Properties(null, null, null, null, downloadTtl, null, null);
	}
}
