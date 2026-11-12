// 미사용 반품 사진과 임시 원본을 정리하고 S3 삭제 실패를 재시도한다.
package com.kitschcatch.backend.domain.order.service;

import com.kitschcatch.backend.domain.order.entity.RefundImage.State;
import com.kitschcatch.backend.domain.order.repository.RefundImageRepository;
import com.kitschcatch.backend.domain.order.storage.RefundImageStorage;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class RefundImageCleanup {
    private final RefundImageRepository images;
    private final RefundImageStorage storage;

    @Transactional
    public void clean(long id) {
        var image = images.lockById(id).orElse(null);
        Instant now = Instant.now();
        if (image == null || image.getCleanupAt().isAfter(now)) return;
        try {
            if (!image.isTemporaryCleaned()) {
                storage.delete(image.getTemporaryKey());
                image.cleanedTemporary();
            }
            if (image.getState() != State.ATTACHED) {
                storage.delete(image.getObjectKey());
                images.delete(image);
            }
        } catch (RuntimeException exception) {
            image.retryCleanup(now);
            log.warn("반품 사진 정리 재시도 예약: imageId={}, attempts={}", id, image.getCleanupAttempts());
        }
    }
}
