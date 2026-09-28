// 실제 SDK 서명 결과와 프로필 저장소의 전송 조건을 AWS 연결 없이 검증한다.
package com.kitschcatch.backend.domain.user.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.any;

import com.kitschcatch.backend.domain.post.storage.S3Properties;
import com.kitschcatch.backend.domain.user.service.ProfileImagePolicy;
import com.kitschcatch.backend.domain.user.service.ProfileImageProperties;
import com.kitschcatch.backend.global.exception.BusinessException;
import com.kitschcatch.backend.global.exception.ErrorCode;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.core.exception.SdkClientException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

class S3ProfileImageStorageTest {
	private final S3Client client = mock(S3Client.class);
	private final ProfileImageProperties profileProperties = new ProfileImageProperties(5_000_000, Duration.ofMinutes(10));
	private S3Presigner presigner;
	private S3ProfileImageStorage storage;

	@BeforeEach
	void setUp() {
		presigner = S3Presigner.builder().region(Region.AP_NORTHEAST_2)
			.credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test-access", "test-secret")))
			.build();
		storage = storage("test-bucket", "https://cdn.example.com/");
	}

	@AfterEach
	void tearDown() {
		presigner.close();
	}

	@Test
	void signsExactTypeLengthAndCreateOnlyConditionWithProfileTtl() {
		Instant before = Instant.now();
		var result = storage.createUploadUrl(42L, "내 사진.JPEG", " IMAGE/JPEG ", 1024);
		String query = URLDecoder.decode(URI.create(result.uploadUrl()).getRawQuery(), StandardCharsets.UTF_8);

		assertThat(result.imageKey()).matches("profiles/42/[0-9a-f-]{36}\\.jpg");
		assertThat(result.imageUrl()).isEqualTo("https://cdn.example.com/" + result.imageKey());
		assertThat(result.expiresIn()).isEqualTo(600);
		assertThat(result.expiresAt()).isBetween(before.plusSeconds(599), Instant.now().plusSeconds(601));
		assertThat(query).contains("X-Amz-Expires=600", "X-Amz-SignedHeaders=content-length;content-type;host;if-none-match");
		assertThat(result.uploadHeaders()).containsEntry("content-type", "image/jpeg").containsEntry("if-none-match", "*")
			.doesNotContainKeys("host", "content-length");
		assertThat(storage.createUploadUrl(42L, "photo.jpg", "image/jpeg", 1024).imageKey()).isNotEqualTo(result.imageKey());
		verifyNoInteractions(client);
	}

	@Test
	void rejectsInvalidInputAndMissingBucket() {
		assertThatThrownBy(() -> storage.createUploadUrl(42L, "photo.png", "image/jpeg", 1))
			.isInstanceOf(BusinessException.class).extracting("errorCode").isEqualTo(ErrorCode.INVALID_INPUT_VALUE);
		assertThatThrownBy(() -> storage("", "").createUploadUrl(42L, "photo.png", "image/png", 1))
			.isInstanceOf(BusinessException.class).extracting("errorCode").isEqualTo(ErrorCode.S3_BUCKET_NOT_CONFIGURED);
		verifyNoInteractions(client);
	}

	@Test
	void encodesLegacyFileNamesAndSupportsS3Fallback() {
		assertThat(storage.imageUrl("profiles/42/내 사진.png"))
			.isEqualTo("https://cdn.example.com/profiles/42/%EB%82%B4%20%EC%82%AC%EC%A7%84.png");
		assertThat(storage("test-bucket", "").imageUrl("profiles/42/image.png"))
			.isEqualTo("https://test-bucket.s3.ap-northeast-2.amazonaws.com/profiles/42/image.png");
	}

	private S3ProfileImageStorage storage(String bucket, String baseUrl) {
		return new S3ProfileImageStorage(client, new S3Properties("ap-northeast-2", bucket, baseUrl, null, null, null),
			presigner, new ProfileImagePolicy(profileProperties), profileProperties);
	}

	@Test
	void readsMetadataWithOneHeadRequest() {
		when(client.headObject(any(HeadObjectRequest.class))).thenReturn(HeadObjectResponse.builder()
			.contentType("image/png").contentLength(1024L).build());
		var metadata = storage.metadata("profiles/42/image.png").orElseThrow();
		assertThat(metadata.contentType()).isEqualTo("image/png");
		assertThat(metadata.contentLength()).isEqualTo(1024);
		verify(client).headObject(HeadObjectRequest.builder().bucket("test-bucket").key("profiles/42/image.png").build());
	}

	@Test
	void returnsEmptyOnlyForMissingObjects() {
		when(client.headObject(any(HeadObjectRequest.class)))
			.thenThrow(S3Exception.builder().statusCode(404).build())
			.thenThrow(NoSuchKeyException.builder().statusCode(404).build());
		assertThat(storage.metadata("profiles/42/missing.png")).isEmpty();
		assertThat(storage.metadata("profiles/42/missing.png")).isEmpty();
	}

	@ParameterizedTest
	@ValueSource(ints = {403, 500, 503})
	void propagatesS3FailuresInsteadOfReportingMissingImage(int status) {
		var failure = S3Exception.builder().statusCode(status).build();
		when(client.headObject(any(HeadObjectRequest.class))).thenThrow(failure);
		assertThatThrownBy(() -> storage.metadata("profiles/42/image.png")).isSameAs(failure);
	}

	@Test
	void propagatesTransportFailures() {
		var failure = SdkClientException.create("test timeout");
		when(client.headObject(any(HeadObjectRequest.class))).thenThrow(failure);
		assertThatThrownBy(() -> storage.metadata("profiles/42/image.png")).isSameAs(failure);
	}
}
