// 정리 대상 행을 잠근 뒤 S3 파일을 삭제하고 실패 시 다음 시도 시각을 저장한다.
package com.kitschcatch.backend.domain.store.service;

import com.kitschcatch.backend.domain.store.entity.StoreReviewImage.State;
import com.kitschcatch.backend.domain.store.repository.StoreReviewImageRepository;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class StoreReviewImageCleanup {
    private final StoreReviewImageRepository images;
    private final StoreReviewImageStorage storage;

    @Transactional
    public void clean(long id) {
        var image = images.lockById(id).orElse(null);
        Instant now = Instant.now();
        if (image == null || image.getState() == State.DELETED || image.getCleanupAt().isAfter(now)) {
            return;
        }
        try {
            if (!image.isTemporaryCleaned()) {
                storage.delete(image.getTemporaryKey());
                image.cleanedTemporary();
            }
            var review = image.getReview();
            boolean expired = (image.getState() == State.PENDING || image.getState() == State.READY)
                && !image.getExpiresAt().isAfter(now);
            boolean unpublished = image.getState() == State.ATTACHED && review.getHiddenAt() == null
                && (review.getDeletedAt() != null || !image.getOwner().isActive());
            if (expired || unpublished || image.getState() == State.DELETE_PENDING) {
                storage.delete(image.getObjectKey());
                image.deleted();
            }
        } catch (RuntimeException exception) {
            image.retry(now);
            log.warn("리뷰 사진 정리 재시도 예약: imageId={}, attempts={}", id, image.getCleanupAttempts());
        }
    }
}
