// 매장 리뷰의 작성 권한, 수정 충돌, 공개 조건과 평점 집계를 관리한다.
package com.kitschcatch.backend.domain.store.service;

import com.kitschcatch.backend.domain.store.dto.CreateStoreReviewRequest;
import com.kitschcatch.backend.domain.store.dto.StoreReviewResponse;
import com.kitschcatch.backend.domain.store.dto.StoreReviewSummary;
import com.kitschcatch.backend.domain.store.dto.UpdateStoreReviewRequest;
import com.kitschcatch.backend.domain.store.entity.StoreReview;
import com.kitschcatch.backend.domain.store.repository.StoreRepository;
import com.kitschcatch.backend.domain.store.repository.StoreReviewRepository;
import com.kitschcatch.backend.domain.user.entity.User;
import com.kitschcatch.backend.domain.user.repository.UserRepository;
import com.kitschcatch.backend.domain.user.service.ProfileImageStorage;
import com.kitschcatch.backend.global.exception.BusinessException;
import com.kitschcatch.backend.global.exception.ErrorCode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class StoreReviewService {
    private static final int MAX_CREATES_PER_STORE = 5;
    private static final Duration CREATE_WINDOW = Duration.ofDays(1);

    private final StoreReviewRepository reviews;
    private final StoreRepository stores;
    private final UserRepository users;
    private final ProfileImageStorage profileImages;

    @Transactional
    public StoreReviewResponse create(long userId, long storeId, CreateStoreReviewRequest request) {
        requirePositiveId(storeId);
        String content = normalizeContent(request.content());
        // 작성과 탈퇴 및 같은 사용자 리뷰 변경을 같은 사용자 행으로 직렬화한다.
        User author = lockActiveUser(userId);
        var store = stores.findById(storeId)
            .orElseThrow(() -> new BusinessException(ErrorCode.STORE_NOT_FOUND));
        if (reviews.existsBlockingCreation(storeId, userId)) {
            throw new BusinessException(ErrorCode.STORE_REVIEW_ALREADY_EXISTS);
        }
        Instant now = now();
        if (reviews.countByAuthorIdAndStoreIdAndCreatedAtGreaterThanEqual(
                userId, storeId, now.minus(CREATE_WINDOW)) >= MAX_CREATES_PER_STORE) {
            throw new BusinessException(ErrorCode.STORE_REVIEW_CREATE_LIMIT);
        }
        return response(reviews.saveAndFlush(new StoreReview(store, author, request.rating().intValueExact(), content, now)),
            userId);
    }

    public StoreReviewResponse.Page list(long userId, long storeId, StorePageQuery query) {
        requireStore(storeId);
        var page = reviews.findPublicPage(storeId, PageRequest.of(query.page(), query.size()));
        return new StoreReviewResponse.Page(page.stream().map(review -> response(review, userId)).toList(),
            page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages());
    }

    public Optional<StoreReviewResponse> mine(long userId, long storeId) {
        requireStore(storeId);
        return reviews.findByStoreIdAndAuthorIdAndDeletedAtIsNull(storeId, userId)
            .map(review -> response(review, userId));
    }

    public StoreReviewResponse detail(long userId, long reviewId) {
        requirePositiveId(reviewId);
        return reviews.findPublicById(reviewId).map(review -> response(review, userId))
            .orElseThrow(() -> new BusinessException(ErrorCode.STORE_REVIEW_NOT_FOUND));
    }

    @Transactional
    public StoreReviewResponse update(long userId, long reviewId, UpdateStoreReviewRequest request) {
        requirePositiveId(reviewId);
        if (request.rating() == null && request.content() == null) {
            throw new BusinessException(ErrorCode.INVALID_INPUT_VALUE);
        }
        String content = request.content() == null ? null : normalizeContent(request.content());
        lockActiveUser(userId);
        var review = ownedReview(userId, reviewId);
        if (review.getDeletedAt() != null) {
            throw new BusinessException(ErrorCode.STORE_REVIEW_NOT_FOUND);
        }
        if (review.getVersion() != request.version()) {
            throw new BusinessException(ErrorCode.STORE_REVIEW_VERSION_CONFLICT);
        }
        int rating = request.rating() == null ? review.getRating() : request.rating().intValueExact();
        review.update(rating, content == null ? review.getContent() : content, now());
        flushReviewChange();
        return response(review, userId);
    }

    @Transactional
    public void delete(long userId, long reviewId) {
        requirePositiveId(reviewId);
        lockActiveUser(userId);
        var review = ownedReview(userId, reviewId);
        if (review.getDeletedAt() == null) {
            review.delete(now());
            flushReviewChange();
        }
    }

    public StoreReviewSummary summary(long storeId) {
        requireStore(storeId);
        return summaryForExistingStore(storeId);
    }

    StoreReviewSummary summaryForExistingStore(long storeId) {
        var counts = new LinkedHashMap<Integer, Long>();
        for (int rating = 1; rating <= 5; rating++) {
            counts.put(rating, 0L);
        }

        long total = 0;
        long sum = 0;
        for (var row : reviews.countPublicByRating(storeId)) {
            counts.put(row.getRating(), row.getReviewCount());
            total += row.getReviewCount();
            sum += row.getRating() * row.getReviewCount();
        }
        var average = total == 0 ? null : BigDecimal.valueOf(sum)
            .divide(BigDecimal.valueOf(total), 2, RoundingMode.HALF_UP);
        return new StoreReviewSummary(total, average, counts, total);
    }

    private StoreReview ownedReview(long userId, long reviewId) {
        var review = reviews.findById(reviewId)
            .orElseThrow(() -> new BusinessException(ErrorCode.STORE_REVIEW_NOT_FOUND));
        if (review.getAuthor().getId() != userId) {
            if (review.getDeletedAt() != null || review.getHiddenAt() != null || !review.getAuthor().isActive()) {
                throw new BusinessException(ErrorCode.STORE_REVIEW_NOT_FOUND);
            }
            throw new BusinessException(ErrorCode.STORE_REVIEW_FORBIDDEN);
        }
        return review;
    }

    private void flushReviewChange() {
        try {
            reviews.flush();
        } catch (OptimisticLockingFailureException exception) {
            throw new BusinessException(ErrorCode.STORE_REVIEW_VERSION_CONFLICT);
        }
    }

    private StoreReviewResponse response(StoreReview review, long userId) {
        var author = review.getAuthor();
        String key = author.getProfileImageKey();
        var authorResponse = new StoreReviewResponse.Author(author.getId(), author.getNickname(),
            key == null ? null : profileImages.imageUrl(key));
        return new StoreReviewResponse(review.getId(), review.getStore().getId(), authorResponse,
            review.getRating(), review.getContent(), review.getCreatedAt(), review.getUpdatedAt(),
            author.getId() == userId, review.getHiddenAt() != null, review.getVersion(), List.of());
    }

    private User lockActiveUser(long userId) {
        return users.findActiveByIdForUpdate(userId)
            .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_AUTH_TOKEN));
    }

    private void requireStore(long storeId) {
        requirePositiveId(storeId);
        if (!stores.existsById(storeId)) {
            throw new BusinessException(ErrorCode.STORE_NOT_FOUND);
        }
    }

    private static void requirePositiveId(long id) {
        if (id <= 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST);
        }
    }

    private static String normalizeContent(String raw) {
        String content = raw.strip();
        if (content.isEmpty() || content.codePointCount(0, content.length()) > 1000 || content.indexOf(0) >= 0) {
            throw new BusinessException(ErrorCode.INVALID_INPUT_VALUE, "리뷰 본문은 앞뒤 공백을 제거한 뒤 1~1000자여야 합니다.");
        }
        return content;
    }

    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }
}
