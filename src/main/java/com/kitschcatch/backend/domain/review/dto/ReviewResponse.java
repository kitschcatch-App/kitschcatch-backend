// 후기 조회에 필요한 공개 평가 필드만 반환한다.
package com.kitschcatch.backend.domain.review.dto;

import com.kitschcatch.backend.domain.review.entity.TransactionReview;
import java.time.LocalDateTime;

public record ReviewResponse(long reviewId, long authorId, long recipientId, String authorRole,
    int rating, String content, LocalDateTime createdAt) {
    public static ReviewResponse from(TransactionReview review) {
        long authorId = review.getAuthor().getId();
        return new ReviewResponse(review.getId(), authorId, review.getRecipient().getId(),
            review.getBuyerId().equals(authorId) ? "BUYER" : "SELLER",
            review.getRating(), review.getContent(), review.getCreatedAt());
    }
}
