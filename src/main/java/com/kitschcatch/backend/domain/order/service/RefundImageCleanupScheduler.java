// 정리 시각이 지난 반품 사진을 작은 배치로 처리한다.
package com.kitschcatch.backend.domain.order.service;

import com.kitschcatch.backend.domain.order.repository.RefundImageRepository;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(name = "app.refund-images.cleanup-enabled", havingValue = "true", matchIfMissing = true)
public class RefundImageCleanupScheduler {
    private final RefundImageRepository images;
    private final RefundImageCleanup cleanup;

    // S3 지연이 결제 복구 등 공용 스케줄러의 작업을 막지 않도록 분리한다.
    @Bean(defaultCandidate = false, destroyMethod = "shutdown")
    static ScheduledExecutorService refundImageCleanupExecutor() {
        return Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("refund-image-cleanup").factory());
    }

    @Scheduled(scheduler = "refundImageCleanupExecutor", initialDelayString = "${app.refund-images.cleanup-delay-ms:60000}",
               fixedDelayString = "${app.refund-images.cleanup-delay-ms:60000}")
    public void run() {
        for (long id : images.due(Instant.now(), PageRequest.of(0, 20))) {
            try {
                cleanup.clean(id);
            } catch (RuntimeException exception) {
                log.warn("반품 사진 정리 트랜잭션 재시도 필요: imageId={}", id);
            }
        }
    }
}
