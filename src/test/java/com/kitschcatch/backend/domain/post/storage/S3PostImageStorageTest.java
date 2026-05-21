package com.kitschcatch.backend.domain.post.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.kitschcatch.backend.domain.post.service.PostImageUploadUrl;
import com.kitschcatch.backend.global.exception.BusinessException;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

class S3PostImageStorageTest {

	@Test
	@DisplayName("S3 presigned URL은 사용자별 게시글 이미지 경로와 공개 URL을 반환한다")
	void createUploadUrlReturnsOwnedImageKeyAndPublicUrl() {
		S3PostImageStorage storage = storage(mock(S3Client.class), properties());

		PostImageUploadUrl uploadUrl = storage.createUploadUrl(1L, "image.png", "image/png");

		assertThat(uploadUrl.uploadUrl()).startsWith("https://kitschcatch-test.s3.ap-northeast-2.amazonaws.com/");
		assertThat(uploadUrl.imageKey()).startsWith("posts/1/");
		assertThat(uploadUrl.imageKey()).endsWith(".png");
		assertThat(uploadUrl.imageUrl()).isEqualTo("https://cdn.example.com/" + uploadUrl.imageKey());
		assertThat(uploadUrl.expiresIn()).isEqualTo(300);
	}

	@Test
	@DisplayName("게시글 이미지 key는 사용자 소유 prefix만 허용한다")
	void isOwnedPostImageKeyAcceptsOnlyUserPrefix() {
		S3PostImageStorage storage = storage(mock(S3Client.class), properties());

		assertThat(storage.isOwnedPostImageKey(1L, "posts/1/image.png")).isTrue();
		assertThat(storage.isOwnedPostImageKey(1L, "posts/2/image.png")).isFalse();
		assertThat(storage.isOwnedPostImageKey(1L, "posts/1/../image.png")).isFalse();
	}

	@Test
	@DisplayName("S3에 없는 게시글 이미지는 존재하지 않는 것으로 반환한다")
	void existsReturnsFalseWhenS3ObjectIsMissing() {
		S3Client s3Client = mock(S3Client.class);
		when(s3Client.headObject(any(HeadObjectRequest.class))).thenThrow(NoSuchKeyException.builder().build());
		S3PostImageStorage storage = storage(s3Client, properties());

		assertThat(storage.exists("posts/1/image.png")).isFalse();
	}

	@Test
	@DisplayName("지원하지 않는 이미지 content type은 거부한다")
	void createUploadUrlRejectsUnsupportedContentType() {
		S3PostImageStorage storage = storage(mock(S3Client.class), properties());

		assertThatThrownBy(() -> storage.createUploadUrl(1L, "image.txt", "text/plain"))
			.isInstanceOf(BusinessException.class);
	}

	private S3PostImageStorage storage(S3Client s3Client, S3Properties properties) {
		return new S3PostImageStorage(s3Presigner(), s3Client, properties);
	}

	private S3Presigner s3Presigner() {
		return S3Presigner.builder()
			.region(Region.AP_NORTHEAST_2)
			.credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("access-key", "secret-key")))
			.build();
	}

	private S3Properties properties() {
		return new S3Properties(
			"ap-northeast-2",
			"kitschcatch-test",
			"https://cdn.example.com",
			Duration.ofMinutes(5),
			"posts"
		);
	}
}
