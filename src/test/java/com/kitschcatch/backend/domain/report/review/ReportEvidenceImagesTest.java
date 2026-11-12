// 실제 S3 SDK가 지연된 이미지 본문까지 제한 시간 안에 처리하는지 검증한다.
package com.kitschcatch.backend.domain.report.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.kitschcatch.backend.domain.post.storage.S3Properties;
import com.kitschcatch.backend.global.exception.BusinessException;
import com.kitschcatch.backend.global.exception.ErrorCode;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

class ReportEvidenceImagesTest {
    @Test
    void slowBodyIsCoveredByTheS3RequestTimeout() throws Exception {
        var headersSent = new CountDownLatch(1);
        var finishBody = new CountDownLatch(1);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try (exchange) {
                exchange.getResponseHeaders().set("Content-Type", "image/png");
                exchange.sendResponseHeaders(200, 3);
                var body = exchange.getResponseBody();
                body.write(1);
                body.flush();
                headersSent.countDown();
                finishBody.await(12, TimeUnit.SECONDS);
                body.write(new byte[] {2, 3});
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            } catch (IOException ignored) {
                // 제한 시간이 지나면 SDK가 연결을 닫는다.
            }
        });
        server.start();

        var properties = new S3Properties("ap-northeast-2", "report-test", null, null, null, null, null);
        try (var s3 = S3Client.builder()
            .endpointOverride(URI.create("http://127.0.0.1:" + server.getAddress().getPort()))
            .forcePathStyle(true)
            .region(Region.AP_NORTHEAST_2)
            .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
            .overrideConfiguration(config -> config.retryStrategy(strategy -> strategy.maxAttempts(1)))
            .build()) {
            var storage = new ReportEvidenceImages(properties, s3);
            assertThatThrownBy(() -> storage.load("chats/1/2/7cd8d012-ec43-4768-8f87-bbb1d2e3c812.png"))
                .isInstanceOfSatisfying(BusinessException.class,
                    error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.REPORT_EVIDENCE_UNAVAILABLE));
            assertThat(headersSent.getCount()).isZero();
        } finally {
            finishBody.countDown();
            server.stop(0);
        }
    }
}
