// 설정으로 활성화된 리뷰 사진 정리를 작은 배치로 실행한다.
package com.kitschcatch.backend.domain.store.service;

import com.kitschcatch.backend.domain.report.ReportProperties;
import com.kitschcatch.backend.domain.store.repository.StoreReviewImageRepository;
import java.time.temporal.ChronoUnit;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(name = "app.store-review-images.cleanup-enabled", havingValue = "true")
public class StoreReviewImageCleanupScheduler {
    private final ReportProperties reports;
    private final StoreReviewImageRepository images;
    private final StoreReviewImageCleanup cleanup;

    @Scheduled(fixedDelayString = "${app.store-review-images.cleanup-delay-ms:60000}")
    public void run() {
        Instant now = Instant.now();
        for (long id : images.due(now, now.minus(reports.retentionDays(), ChronoUnit.DAYS), PageRequest.of(0, 20))) {
            try {
                cleanup.clean(id);
            } catch (RuntimeException exception) {
                log.warn("리뷰 사진 정리 트랜잭션 재시도 필요: imageId={}", id);
            }
        }
    }
}
