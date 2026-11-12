// 리뷰 사진 S3 서명, 실제 응답 검증, 조건부 저장 재시도와 버전 삭제를 검증한다.
package com.kitschcatch.backend.domain.store.storage;

import static com.kitschcatch.backend.support.S3GetUrlAssertions.assertGetUrl;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.kitschcatch.backend.domain.post.storage.S3Properties;
import com.kitschcatch.backend.global.exception.BusinessException;
import com.kitschcatch.backend.global.exception.ErrorCode;
import java.io.ByteArrayInputStream;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.auth.credentials.*;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

class S3StoreReviewImageStorageTest {
    private static final String TEMP = "store-review-uploads/1/879ad4f1-4313-467e-bbc6-e29bdf99ac0e";
    private static final String FINAL = "store-review-images/1/1/879ad4f1-4313-467e-bbc6-e29bdf99ac0e.png";
    private final S3Client client = mock(S3Client.class);
    private S3Presigner presigner;
    private S3StoreReviewImageStorage storage;

    @BeforeEach
    void setup() {
        presigner = S3Presigner.builder().region(Region.AP_NORTHEAST_2)
            .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test-access", "test-secret"))).build();
        storage = new S3StoreReviewImageStorage(client, presigner,
            new S3Properties("ap-northeast-2", "review-test-bucket", null, null, Duration.ofMinutes(12), null, null));
    }
    @AfterEach void close() { presigner.close(); }

    @Test
    void signsPutWithExactMetadataAndCreateOnlyConditionAndSignsGet() {
        var upload = storage.upload(TEMP, "image/png", 3);
        assertThat(upload.headers()).containsEntry("content-type", "image/png")
            .containsEntry("content-length", "3").containsEntry("if-none-match", "*");
        assertThat(upload.url()).contains("X-Amz-Expires=300", "X-Amz-SignedHeaders=content-length%3Bcontent-type%3Bhost%3Bif-none-match");
        assertGetUrl(storage.imageUrl(FINAL), "review-test-bucket", FINAL, 720, "test-access", "test-secret");
        assertThatThrownBy(() -> storage.imageUrl("profiles/1/other.jpg")).isInstanceOf(BusinessException.class);
    }

    @Test
    void readsExactBytesAndRejectsMetadataOrBodyMismatch() {
        when(client.getObject(any(GetObjectRequest.class))).thenReturn(stream(3, "image/png", new byte[] {1, 2, 3}));
        assertThat(storage.read(TEMP, "image/png", 3)).containsExactly(1, 2, 3);
        when(client.getObject(any(GetObjectRequest.class))).thenReturn(stream(4, "image/png", new byte[] {1, 2, 3, 4}));
        assertInvalid(() -> storage.read(TEMP, "image/png", 3));
        when(client.getObject(any(GetObjectRequest.class))).thenReturn(stream(3, "image/jpeg", new byte[] {1, 2, 3}));
        assertInvalid(() -> storage.read(TEMP, "image/png", 3));
        when(client.getObject(any(GetObjectRequest.class))).thenReturn(stream(3, "image/png", new byte[] {1, 2}));
        assertInvalid(() -> storage.read(TEMP, "image/png", 3));
        when(client.getObject(any(GetObjectRequest.class))).thenReturn(stream(3, "image/png", new byte[] {1, 2, 3, 4}));
        assertInvalid(() -> storage.read(TEMP, "image/png", 3));
    }

    @Test
    void mapsMissingObjectAndNetworkFailures() {
        when(client.getObject(any(GetObjectRequest.class))).thenThrow(S3Exception.builder().statusCode(404).build());
        assertThatThrownBy(() -> storage.read(TEMP, "image/png", 3)).isInstanceOfSatisfying(BusinessException.class,
            error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.STORE_REVIEW_IMAGE_NOT_UPLOADED));
        when(client.getObject(any(GetObjectRequest.class))).thenThrow(SdkClientException.create("network unavailable"));
        assertThatThrownBy(() -> storage.read(TEMP, "image/png", 3)).isInstanceOfSatisfying(BusinessException.class,
            error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.STORE_REVIEW_IMAGE_STORAGE_UNAVAILABLE));
    }

    @Test
    void publishesOnceAndAcceptsOnlyIdenticalReplayAfterDatabaseRollback() {
        byte[] bytes = {1, 2, 3};
        storage.publish(FINAL, bytes);
        var request = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(client).putObject(request.capture(), any(RequestBody.class));
        assertThat(request.getValue().ifNoneMatch()).isEqualTo("*");
        assertThat(request.getValue().contentType()).isEqualTo("image/png");
        when(client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
            .thenThrow(S3Exception.builder().statusCode(412).build());
        when(client.headObject(any(HeadObjectRequest.class))).thenReturn(HeadObjectResponse.builder()
            .contentLength(3L).contentType("image/png").metadata(request.getValue().metadata()).build());
        storage.publish(FINAL, bytes);
        when(client.headObject(any(HeadObjectRequest.class))).thenReturn(HeadObjectResponse.builder()
            .contentLength(3L).contentType("image/png").metadata(Map.of("sha256", "different")).build());
        assertInvalid(() -> storage.publish(FINAL, bytes));
        when(client.headObject(any(HeadObjectRequest.class))).thenThrow(SdkClientException.create("network"));
        assertThatThrownBy(() -> storage.publish(FINAL, bytes)).isInstanceOfSatisfying(BusinessException.class,
            error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.STORE_REVIEW_IMAGE_STORAGE_UNAVAILABLE));
    }

    @Test
    void deletesAllVersionsAndMarkersWithoutDeletingKeysThatOnlySharePrefix() {
        when(client.listObjectVersions(any(ListObjectVersionsRequest.class)))
            .thenReturn(ListObjectVersionsResponse.builder().versions(
                ObjectVersion.builder().key(FINAL).versionId("one").build(),
                ObjectVersion.builder().key(FINAL).versionId("two").build(),
                ObjectVersion.builder().key(FINAL + "-other").versionId("unrelated").build())
                .deleteMarkers(DeleteMarkerEntry.builder().key(FINAL).versionId("marker").build()).build())
            .thenReturn(ListObjectVersionsResponse.builder().build());
        storage.delete(FINAL);
        var deleted = ArgumentCaptor.forClass(DeleteObjectRequest.class);
        verify(client, times(3)).deleteObject(deleted.capture());
        assertThat(deleted.getAllValues()).extracting(DeleteObjectRequest::key).containsOnly(FINAL);
        assertThat(deleted.getAllValues()).extracting(DeleteObjectRequest::versionId).containsExactly("one", "two", "marker");
    }

    private ResponseInputStream<GetObjectResponse> stream(long size, String type, byte[] bytes) {
        return new ResponseInputStream<>(GetObjectResponse.builder().contentLength(size).contentType(type).build(),
            AbortableInputStream.create(new ByteArrayInputStream(bytes)));
    }
    private void assertInvalid(org.assertj.core.api.ThrowableAssert.ThrowingCallable operation) {
        assertThatThrownBy(operation).isInstanceOfSatisfying(BusinessException.class,
            error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.STORE_REVIEW_IMAGE_INVALID));
    }
}
