// 사진 업로드 완료, 리뷰 연결 권한과 공개 사진 조회를 관리한다.
package com.kitschcatch.backend.domain.store.service;

import com.kitschcatch.backend.domain.store.dto.StoreReviewImageDtos.*;
import com.kitschcatch.backend.domain.store.dto.StoreReviewResponse;
import com.kitschcatch.backend.domain.store.entity.StoreReview;
import com.kitschcatch.backend.domain.store.entity.StoreReviewImage;
import com.kitschcatch.backend.domain.store.entity.StoreReviewImage.State;
import com.kitschcatch.backend.domain.store.repository.*;
import com.kitschcatch.backend.domain.user.repository.UserRepository;
import com.kitschcatch.backend.global.exception.BusinessException;
import com.kitschcatch.backend.global.exception.ErrorCode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class StoreReviewImageService {
    private final StoreReviewImageRepository images;
    private final StoreRepository stores;
    private final UserRepository users;
    private final StoreReviewImageStorage storage;
    private final StoreReviewImageCodec codec;
    private static final Map<String, Set<String>> EXTENSIONS = Map.of(
        "image/jpeg", Set.of("jpg", "jpeg"), "image/png", Set.of("png"), "image/webp", Set.of("webp"));

    @Transactional
    public Uploads upload(long userId, long storeId, UploadRequest request) {
        var owner = users.findActiveByIdForUpdate(userId)
            .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_AUTH_TOKEN));
        requireStore(storeId);
        if (images.countByOwnerIdAndCreatedAtGreaterThanEqual(userId, Instant.now().minusSeconds(86400))
                + request.images().size() > 100) {
            throw new BusinessException(ErrorCode.STORE_REVIEW_IMAGE_BUSY);
        }
        var store = stores.getReferenceById(storeId);
        var result = new ArrayList<Upload>();
        for (var file : request.images()) {
            var allowed = EXTENSIONS.get(file.contentType());
            String extension = file.fileName().substring(file.fileName().lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
            if (allowed == null || !allowed.contains(extension) || file.fileSize() < 1
                    || file.fileSize() > StoreReviewImageCodec.MAX_BYTES) {
                throw new BusinessException(ErrorCode.STORE_REVIEW_IMAGE_INVALID);
            }
            var image = images.save(new StoreReviewImage(owner, store, UUID.randomUUID().toString(),
                file.contentType(), file.fileSize(), Instant.now().truncatedTo(ChronoUnit.MICROS)));
            var signed = storage.upload(image.getTemporaryKey(), file.contentType(), file.fileSize());
            result.add(new Upload(image.getId(), signed.url(), signed.expiresAt(), signed.headers()));
        }
        return new Uploads(result);
    }

    @Transactional
    public Completed complete(long userId, long imageId) {
        users.findActiveByIdForUpdate(userId)
            .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_AUTH_TOKEN));
        var image = lock(imageId);
        if (image.getOwner().getId() != userId) {
            throw new BusinessException(ErrorCode.STORE_REVIEW_IMAGE_NOT_FOUND);
        }
        if (image.getState() == State.ATTACHED || image.getState() == State.READY
                && image.getExpiresAt().isAfter(Instant.now())) {
            return completed(image);
        }
        if (image.getState() != State.PENDING || !image.getExpiresAt().isAfter(Instant.now())) {
            throw new BusinessException(ErrorCode.STORE_REVIEW_IMAGE_EXPIRED);
        }
        var clean = codec.process(
            () -> storage.read(image.getTemporaryKey(), image.getContentType(), image.getExpectedSize()),
            image.getContentType(), normalized -> {
                // DB 롤백 뒤에도 동일 키와 바이트로 재시도할 수 있도록 조건부 최종 저장을 사용한다.
                storage.publish(image.getObjectKey(), normalized.png());
                return normalized;
            });
        image.ready(clean.width(), clean.height());
        images.flush();
        return completed(image);
    }

    @Transactional
    public void replace(StoreReview review, List<Long> requested) {
        if (requested == null) {
            return;
        }
        if (review.getHiddenAt() != null) {
            throw new BusinessException(ErrorCode.STORE_REVIEW_IMAGE_HIDDEN);
        }
        if (requested.size() > 10 || requested.stream().anyMatch(id -> id == null || id <= 0)
                || new HashSet<>(requested).size() != requested.size()) {
            throw new BusinessException(ErrorCode.STORE_REVIEW_IMAGE_INVALID);
        }
        var old = images.findByReviewIdOrderBySortOrder(review.getId());
        var ids = new TreeSet<>(requested);
        old.forEach(image -> ids.add(image.getId()));
        var locked = new HashMap<Long, StoreReviewImage>();
        for (long id : ids) {
            locked.put(id, lock(id));
        }
        for (long id : requested) {
            var image = locked.get(id);
            if (!image.getOwner().getId().equals(review.getAuthor().getId())
                    || !image.getStore().getId().equals(review.getStore().getId())) {
                throw new BusinessException(ErrorCode.STORE_REVIEW_IMAGE_NOT_FOUND);
            }
            boolean retained = image.getState() == State.ATTACHED && image.getReview().getId().equals(review.getId());
            if (!retained && (image.getState() != State.READY || !image.getExpiresAt().isAfter(Instant.now()))) {
                throw new BusinessException(ErrorCode.STORE_REVIEW_IMAGE_EXPIRED);
            }
        }
        for (var previous : old) {
            var image = locked.get(previous.getId());
            if (requested.contains(image.getId())) {
                image.release();
            } else {
                image.retire(Instant.now());
            }
        }
        // 순서 고유 제약이 중간 UPDATE 순서에 영향받지 않도록 같은 트랜잭션에서 먼저 비운다.
        images.flush();
        for (int order = 0; order < requested.size(); order++) {
            locked.get(requested.get(order)).attach(review, order);
        }
        images.flush();
    }

    @Transactional
    public void retire(StoreReview review) {
        if (review.getHiddenAt() != null) {
            return;
        }
        images.findByReviewIdOrderBySortOrder(review.getId()).stream()
            .map(StoreReviewImage::getId).sorted().map(this::lock).forEach(image -> image.retire(Instant.now()));
        images.flush();
    }

    public Map<Long, List<StoreReviewResponse.Image>> responses(List<Long> reviewIds) {
        if (reviewIds.isEmpty()) {
            return Map.of();
        }
        return images.findByReviewIds(reviewIds).stream().collect(Collectors.groupingBy(
            image -> image.getReview().getId(), LinkedHashMap::new,
            Collectors.mapping(image -> new StoreReviewResponse.Image(image.getId(),
                storage.imageUrl(image.getObjectKey()), image.getSortOrder()), Collectors.toList())));
    }

    public Gallery gallery(long storeId, StorePageQuery query) {
        requireStore(storeId);
        var page = images.gallery(storeId, PageRequest.of(query.page(), query.size()));
        return new Gallery(page.stream().map(image -> new GalleryImage(image.getId(), image.getReview().getId(),
            image.getOwner().getId(), image.getOwner().getNickname(), storage.imageUrl(image.getObjectKey()),
            image.getSortOrder(), image.getReview().getCreatedAt())).toList(),
            page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages());
    }

    private StoreReviewImage lock(long id) {
        return images.lockById(id).orElseThrow(() -> new BusinessException(ErrorCode.STORE_REVIEW_IMAGE_NOT_FOUND));
    }

    private void requireStore(long id) {
        if (id <= 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST);
        }
        if (!stores.existsById(id)) {
            throw new BusinessException(ErrorCode.STORE_NOT_FOUND);
        }
    }

    private Completed completed(StoreReviewImage image) {
        return new Completed(image.getId(), image.getState(), image.getWidth(), image.getHeight());
    }
}
