package com.kitschcatch.backend.domain.post.storage;

import static com.kitschcatch.backend.support.S3GetUrlAssertions.assertGetUrl;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;

import com.kitschcatch.backend.domain.post.service.PostImageUploadUrl;
import com.kitschcatch.backend.global.exception.BusinessException;
import com.kitschcatch.backend.global.exception.ErrorCode;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

class S3PostImageStorageTest {
	private final S3Presigner presigner = s3Presigner();

	@AfterEach
	void tearDown() {
		presigner.close();
	}

	@Test
	@DisplayName("게시글 업로드 URL과 별도의 GET 서명 URL을 반환한다")
	void createUploadUrlReturnsOwnedImageKeyAndSignedGetUrl() {
		S3PostImageStorage storage = storage(mock(S3Client.class), properties());

		PostImageUploadUrl uploadUrl = storage.createUploadUrl(1L, "image.png", "image/png");

		assertThat(uploadUrl.uploadUrl()).startsWith("https://kitschcatch-test.s3.ap-northeast-2.amazonaws.com/");
		assertThat(uploadUrl.imageKey()).startsWith("posts/1/");
		assertThat(uploadUrl.imageKey()).endsWith(".png");
		assertGetUrl(uploadUrl.imageUrl(), "kitschcatch-test", uploadUrl.imageKey(), 900, "access-key", "secret-key");
		assertThat(uploadUrl.expiresIn()).isEqualTo(300);
	}

	@ParameterizedTest
	@ValueSource(strings = {"", "https://cdn.example.com"})
	void signsExistingKeysWithGetTtlRegardlessOfPublicBaseUrl(String baseUrl) {
		S3Client client = mock(S3Client.class);
		var properties = new S3Properties("ap-northeast-2", "kitschcatch-test", baseUrl,
			Duration.ofMinutes(5), Duration.ofMinutes(20), null, null);
		String key = "posts/1/내 사진+?#%.png";
		assertGetUrl(storage(client, properties).imageUrl(key), "kitschcatch-test", key, 1200, "access-key", "secret-key");
		verifyNoInteractions(client);
	}

	@Test
	void includesSessionTokenWhenSigningWithTemporaryCredentials() {
		try (S3Presigner sessionPresigner = S3Presigner.builder().region(Region.AP_NORTHEAST_2)
			.credentialsProvider(StaticCredentialsProvider.create(AwsSessionCredentials.create("session-access", "session-secret", "session/token+=")))
			.build()) {
			var storage = new S3PostImageStorage(sessionPresigner, mock(S3Client.class), properties());
			String url = storage.imageUrl("posts/1/image.png");
			assertThat(url).contains("X-Amz-Security-Token=session%2Ftoken%2B%3D");
			assertGetUrl(url, "kitschcatch-test", "posts/1/image.png", 900, "session-access", "session-secret");
		}
	}

	@Test
	void rejectsMissingBucketEvenWhenPublicBaseUrlIsConfigured() {
		var properties = new S3Properties("ap-northeast-2", "", "https://cdn.example.com", null, null, null, null);
		assertThatThrownBy(() -> storage(mock(S3Client.class), properties).imageUrl("posts/1/image.png"))
			.isInstanceOf(BusinessException.class).extracting("errorCode").isEqualTo(ErrorCode.S3_BUCKET_NOT_CONFIGURED);
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
		return new S3PostImageStorage(presigner, s3Client, properties);
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
			null,
			"posts",
			"chats"
		);
	}
}
