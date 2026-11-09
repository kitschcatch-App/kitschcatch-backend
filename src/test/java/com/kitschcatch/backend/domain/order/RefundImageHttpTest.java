// 주문별 반품 사진 제출과 조회 권한, 재요청, 정리 및 PG 회귀를 HTTP로 검증한다.
package com.kitschcatch.backend.domain.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.kitschcatch.backend.domain.order.service.RefundImageCleanup;
import com.kitschcatch.backend.domain.order.toss.TossPaymentResponse;
import com.kitschcatch.backend.support.S3GetUrlAssertions;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.annotation.DirtiesContext;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.datasource.url=jdbc:h2:mem:refund-images;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "spring.jpa.hibernate.ddl-auto=create", "kakao.oauth.native-app-key=test-native-app-key",
    "app.orders.expiration-enabled=false", "app.payments.recovery.enabled=false",
    "app.refund-images.cleanup-enabled=false", "app.refunds.operator-user-ids=900000",
    "app.s3.bucket=refund-test-bucket"
})
@Import(RefundImageHttpContract.SigningConfig.class)
@DirtiesContext
class RefundImageHttpTest extends RefundImageHttpContract {}

abstract class RefundImageHttpContract extends OrderLifecycleHttpFixture {
    private static final String BUCKET = "refund-test-bucket";
    private static final byte[] PNG = testPhoto();
    @Autowired RefundImageCleanup cleanup;
    @Autowired AtomicBoolean signingUnavailable;
    private String operatorToken;

    @Override
    @BeforeEach
    void resetData() {
        jdbc.update("DELETE FROM refund_images");
        super.resetData();
        jdbc.update("""
            INSERT INTO users(id,nickname,email,auth_provider,provider_user_id,created_at)
            VALUES(900000,'반품 담당','return-photo-operator@example.test','KAKAO','return-photo-operator',CURRENT_TIMESTAMP)
            """);
        operatorToken = tokens.createAccessToken(900000L);
        when(s3.getObject(any(GetObjectRequest.class))).thenAnswer(call -> uploaded("image/png", PNG));
        when(s3.listObjectVersions(any(ListObjectVersionsRequest.class)))
            .thenReturn(ListObjectVersionsResponse.builder().build());
    }

    @Test
    void attachesInSubmittedOrderAndSignsOnlyForPartiesAndOperator() {
        var order = paidAndShipped();
        int first = ready(order);
        int second = ready(order);
        var result = refund(order, List.of(second, first));
        assertThat(result.status()).isEqualTo(200);
        assertThat(result.data()).containsEntry("status", "REQUESTED");
        assertThat(ids(result.data())).containsExactly(second, first);
        verify(toss, never()).cancel(any());

        for (String token : List.of(buyerToken, sellerToken, operatorToken)) {
            var view = get(path(order) + "/refunds", token);
            assertThat(view.status()).isEqualTo(200);
            var photo = photos(view.data()).getFirst();
            assertThat(photo.keySet()).containsExactlyInAnyOrder("imageId", "imageUrl", "expiresAt", "sortOrder");
            assertThat(photo).containsEntry("imageId", second).containsEntry("sortOrder", 0);
            assertThat(Instant.parse((String) photo.get("expiresAt"))).isAfter(Instant.now());
            String finalKey = jdbc.queryForObject("SELECT object_key FROM refund_images WHERE id = ?", String.class, second);
            S3GetUrlAssertions.assertGetUrl((String) photo.get("imageUrl"), BUCKET, finalKey, 300, "photo-access", "photo-secret");
        }
        assertThat(ids(child(get(path(order), sellerToken).data(), "refund"))).containsExactly(second, first);
        assertError(get(path(order) + "/refunds", strangerToken), 403, "ORDER_004");
        assertError(get(path(order), strangerToken), 403, "ORDER_004");
        assertError(get(path(order) + "/refunds", null), 401, "AUTH_004");
    }

    @Test
    void rejectsWrongOwnerOrderAndUnverifiedPhotoWithoutPartialAttachment() {
        var order = paidAndShipped();
        var otherOrder = paidAndShipped();
        int first = ready(order);
        int pending = issue(order);
        int other = ready(otherOrder);

        assertError(upload(order, sellerToken, file("damage.png", "image/png", PNG.length)), 403, "ORDER_004");
        assertError(complete(order, first, strangerToken), 403, "ORDER_004");
        assertError(complete(otherOrder, first, buyerToken), 404, "REFUND_IMAGE_002");
        assertError(refund(order, List.of(first, other)), 404, "REFUND_IMAGE_002");
        assertError(refund(order, List.of(first, pending)), 409, "REFUND_IMAGE_003");
        assertError(refund(order, List.of(first, first)), 400, "REFUND_IMAGE_001");
        assertThat(refund(order, List.of(1, 2, 3, 4, 5, 6)).status()).isEqualTo(400);
        assertError(get(path(order) + "/refunds", buyerToken), 404, "REFUND_003");
        assertThat(jdbc.queryForObject("SELECT state FROM refund_images WHERE id = ?", String.class, first))
            .isEqualTo("READY");
        assertThat(refund(order, List.of(first)).status()).isEqualTo(200);
    }

    @Test
    void validatesActualBytesAndKeepsCompletionRetryable() {
        var order = paidAndShipped();
        assertError(upload(order, buyerToken, file("damage.txt", "image/png", PNG.length)), 400, "REFUND_IMAGE_001");
        assertThat(upload(order, buyerToken, file("damage.png", "image/png", 5242881)).status()).isEqualTo(400);
        int id = issue(order);
        doThrow(S3Exception.builder().statusCode(404).build()).when(s3).getObject(any(GetObjectRequest.class));
        assertError(complete(order, id, buyerToken), 409, "REFUND_IMAGE_003");
        doThrow(S3Exception.builder().statusCode(503).build()).when(s3).getObject(any(GetObjectRequest.class));
        assertError(complete(order, id, buyerToken), 503, "REFUND_IMAGE_006");
        doAnswer(call -> uploaded("image/jpeg", PNG)).when(s3).getObject(any(GetObjectRequest.class));
        assertError(complete(order, id, buyerToken), 400, "REFUND_IMAGE_001");
        when(s3.getObject(any(GetObjectRequest.class))).thenAnswer(call -> uploaded("image/png", new byte[PNG.length]));
        assertError(complete(order, id, buyerToken), 400, "REFUND_IMAGE_001");
        verify(s3, never()).putObject(any(PutObjectRequest.class), any(RequestBody.class));
        when(s3.getObject(any(GetObjectRequest.class))).thenAnswer(call -> uploaded("image/png", PNG));
        var completed = complete(order, id, buyerToken);
        assertThat(completed.status()).isEqualTo(200);
        assertThat(completed.data()).containsEntry("state", "READY").containsEntry("width", 3).containsEntry("height", 2);
        assertThat(complete(order, id, buyerToken).data()).isEqualTo(completed.data());
        verify(s3, times(1)).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }

    @Test
    void preservesLegacyRequestAndRejectsNewUploadAfterRefund() {
        var order = paidAndShipped();
        var result = request("POST", path(order) + "/refunds", buyerToken,
            Map.of("amount", 12000, "reason", "파손 증빙"));
        assertThat(result.status()).isEqualTo(200);
        assertThat(photos(result.data())).isEmpty();
        assertThat(refund(order, List.of()).data()).isEqualTo(result.data());
        assertError(upload(order, buyerToken, file("damage.png", "image/png", PNG.length)), 409, "REFUND_002");
    }

    @Test
    void approvalAndPhotoReplayDoNotRepeatPgCancellation() {
        var order = paidAndShipped();
        int first = ready(order);
        int second = ready(order);
        var requested = refund(order, List.of(first, second));
        var decision = Map.of("refundId", requested.data().get("refundId"), "reason", "실물 반품 확인", "returnReceived", true);
        expire(first);
        expire(second);
        assertError(refund(order, List.of(second, first)), 409, "REFUND_002");
        assertThat(refund(order, List.of(first, second)).data().get("refundId")).isEqualTo(requested.data().get("refundId"));

        when(toss.cancel(any())).thenReturn(new TossPaymentResponse("pg-key-" + order.paymentId(), order.orderId(), 12000L, "CANCELED"));
        for (int i = 0; i < 2; i++) {
            var result = request("POST", path(order) + "/refunds/approve", operatorToken, decision);
            assertThat(result.status()).isEqualTo(200);
            assertThat(result.data()).containsEntry("status", "COMPLETED");
            assertThat(ids(result.data())).containsExactly(first, second);
        }
        verify(toss, times(1)).cancel(any());
    }

    @Test
    void photoSigningFailureDoesNotStopAnApprovedPgCancellation() {
        var order = paidAndShipped();
        int id = ready(order);
        var requested = refund(order, List.of(id));
        var decision = Map.of("refundId", requested.data().get("refundId"), "reason", "실물 반품 확인", "returnReceived", true);
        when(toss.cancel(any())).thenReturn(new TossPaymentResponse("pg-key-" + order.paymentId(), order.orderId(), 12000L, "CANCELED"));

        signingUnavailable.set(true);
        try {
            assertError(request("POST", path(order) + "/refunds/approve", operatorToken, decision),
                503, "REFUND_IMAGE_006");
        } finally {
            signingUnavailable.set(false);
        }

        var result = request("POST", path(order) + "/refunds/approve", operatorToken, decision);
        assertThat(result.status()).isEqualTo(200);
        assertThat(result.data()).containsEntry("status", "COMPLETED");
        assertThat(ids(result.data())).containsExactly(id);
        verify(toss, times(1)).cancel(any());
    }

    @Test
    void blocksExpiredAttachmentAndRetriesDeletionWithoutRemovingAttachedEvidence() {
        var order = paidAndShipped();
        int retained = ready(order);
        int abandoned = ready(order);
        expire(abandoned);
        assertError(refund(order, List.of(abandoned)), 409, "REFUND_IMAGE_004");
        assertThat(refund(order, List.of(retained)).status()).isEqualTo(200);
        expire(retained);
        String retainedKey = jdbc.queryForObject("SELECT object_key FROM refund_images WHERE id = ?", String.class, retained);
        String abandonedKey = jdbc.queryForObject("SELECT object_key FROM refund_images WHERE id = ?", String.class, abandoned);

        when(s3.listObjectVersions(any(ListObjectVersionsRequest.class)))
            .thenThrow(S3Exception.builder().statusCode(503).build())
            .thenReturn(ListObjectVersionsResponse.builder().build());
        cleanup.clean(abandoned);
        assertThat(jdbc.queryForObject("SELECT cleanup_attempts FROM refund_images WHERE id = ?", Integer.class, abandoned)).isEqualTo(1);
        expire(abandoned);
        cleanup.clean(abandoned);
        cleanup.clean(retained);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM refund_images WHERE id = ?", Integer.class, abandoned)).isZero();
        assertThat(jdbc.queryForObject("SELECT temporary_cleaned FROM refund_images WHERE id = ?", Boolean.class, retained)).isTrue();
        verify(s3).listObjectVersions(argThat((ListObjectVersionsRequest query) -> query.prefix().equals(abandonedKey)));
        verify(s3, never()).listObjectVersions(argThat((ListObjectVersionsRequest query) -> query.prefix().equals(retainedKey)));
        assertThat(ids(get(path(order) + "/refunds", sellerToken).data())).containsExactly(retained);
    }

    @Test
    void concurrentSubmissionsWithDifferentEvidenceCommitExactlyOneSet() throws Exception {
        var order = paidAndShipped();
        int first = ready(order);
        int second = ready(order);
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> { start.await(); return refund(order, List.of(first)); });
            var b = pool.submit(() -> { start.await(); return refund(order, List.of(second)); });
            start.countDown();
            var resultA = a.get(10, TimeUnit.SECONDS);
            var resultB = b.get(10, TimeUnit.SECONDS);
            assertThat(List.of(resultA.status(), resultB.status())).containsExactlyInAnyOrder(200, 409);
            assertThat(ids(get(path(order) + "/refunds", sellerToken).data()))
                .containsExactly(resultA.status() == 200 ? first : second);
        }
    }

    @Test
    void cleanupRacingAnExpiredAttachmentLeavesNoRefundOrImage() throws Exception {
        var order = paidAndShipped();
        int id = ready(order);
        expire(id);
        var deleting = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(s3.listObjectVersions(any(ListObjectVersionsRequest.class))).thenAnswer(call -> {
            deleting.countDown();
            assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
            return ListObjectVersionsResponse.builder().build();
        });
        try (var pool = Executors.newFixedThreadPool(2)) {
            var removal = pool.submit(() -> cleanup.clean(id));
            try {
                assertThat(deleting.await(10, TimeUnit.SECONDS)).isTrue();
                var request = pool.submit(() -> refund(order, List.of(id)));
                release.countDown();
                removal.get(10, TimeUnit.SECONDS);
                assertError(request.get(10, TimeUnit.SECONDS), 404, "REFUND_IMAGE_002");
            } finally {
                release.countDown();
            }
        }
        assertError(get(path(order) + "/refunds", buyerToken), 404, "REFUND_003");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM refund_images WHERE id = ?", Integer.class, id)).isZero();
    }

    @Test
    void unshippedRefundWithPhotoUsesTheExistingPgCancellation() {
        var order = order(buyer, seller, false);
        assertError(upload(order, buyerToken, file("damage.png", "image/png", PNG.length)), 409, "ORDER_005");
        approve(order);
        int id = ready(order);
        when(toss.cancel(any())).thenReturn(new TossPaymentResponse("pg-key-" + order.paymentId(), order.orderId(), 12000L, "CANCELED"));
        var result = refund(order, List.of(id));
        assertThat(result.status()).isEqualTo(200);
        assertThat(result.data()).containsEntry("status", "COMPLETED");
        assertThat(ids(result.data())).containsExactly(id);
        assertThat(refund(order, List.of(id)).status()).isEqualTo(200);
        verify(toss, times(1)).cancel(any());
    }

    private OrderFixture paidAndShipped() {
        var order = order(buyer, seller, false);
        approve(order);
        var response = request("POST", path(order) + "/shipment", sellerToken,
            Map.of("carrierCode", "CJ_LOGISTICS", "trackingNumber", "123456789012"));
        assertThat(response.status()).isEqualTo(200);
        return order;
    }

    private int ready(OrderFixture order) {
        int id = issue(order);
        assertThat(complete(order, id, buyerToken).status()).isEqualTo(200);
        return id;
    }

    private int issue(OrderFixture order) {
        var result = upload(order, buyerToken, file("damage.png", "image/png", PNG.length));
        assertThat(result.status()).isEqualTo(200);
        var signed = photos(result.data()).getFirst();
        assertThat((String) signed.get("uploadUrl")).contains("X-Amz-Expires=300", "/refund-uploads/");
        assertThat(child(signed, "headers")).containsEntry("content-length", String.valueOf(PNG.length))
            .containsEntry("content-type", "image/png").containsEntry("if-none-match", "*");
        return (Integer) signed.get("imageId");
    }

    private Response upload(OrderFixture order, String token, Map<String, Object> file) {
        return request("POST", path(order) + "/refund-images/upload-urls", token, Map.of("images", List.of(file)));
    }

    private Response complete(OrderFixture order, int imageId, String token) {
        return request("POST", path(order) + "/refund-images/" + imageId + "/complete", token, null);
    }

    private Response refund(OrderFixture order, List<Integer> ids) {
        return request("POST", path(order) + "/refunds", buyerToken,
            Map.of("amount", 12000, "reason", "파손 증빙", "imageIds", ids));
    }

    private void expire(int id) {
        var yesterday = java.sql.Timestamp.from(Instant.now().minusSeconds(90000));
        var past = java.sql.Timestamp.from(Instant.now().minusSeconds(3600));
        jdbc.update("UPDATE refund_images SET created_at=?, expires_at=?, cleanup_at=? WHERE id=?", yesterday, past, past, id);
    }

    private static Map<String, Object> file(String name, String type, int size) {
        return Map.of("fileName", name, "contentType", type, "fileSize", size);
    }

    private static String path(OrderFixture order) {
        return "/api/orders/" + order.orderId();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> photos(Map<String, Object> data) {
        return (List<Map<String, Object>>) data.get("images");
    }

    private static List<Integer> ids(Map<String, Object> data) {
        return photos(data).stream().map(photo -> (Integer) photo.get("imageId")).toList();
    }

    private static ResponseInputStream<GetObjectResponse> uploaded(String type, byte[] content) {
        return new ResponseInputStream<>(GetObjectResponse.builder()
            .contentType(type).contentLength((long) content.length).build(),
            AbortableInputStream.create(new ByteArrayInputStream(content)));
    }

    private static byte[] testPhoto() {
        try (var output = new ByteArrayOutputStream()) {
            ImageIO.write(new BufferedImage(3, 2, BufferedImage.TYPE_INT_RGB), "png", output);
            return output.toByteArray();
        } catch (java.io.IOException error) {
            throw new IllegalStateException(error);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class SigningConfig {
        @Bean
        AtomicBoolean signingUnavailable() {
            return new AtomicBoolean();
        }

        @Bean @Primary
        S3Presigner refundTestPresigner(AtomicBoolean signingUnavailable) {
            return S3Presigner.builder().region(Region.AP_NORTHEAST_2)
                .credentialsProvider(() -> {
                    if (signingUnavailable.get()) throw SdkClientException.create("test credential outage");
                    return AwsBasicCredentials.create("photo-access", "photo-secret");
                })
                .build();
        }
    }
}
