// 리뷰 사진의 업로드 상태, 소유권, 리뷰 연결과 정리 재시도를 저장한다.
package com.kitschcatch.backend.domain.store.entity;

import com.kitschcatch.backend.domain.user.entity.User;
import jakarta.persistence.*;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "store_review_images")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StoreReviewImage {
    public enum State { PENDING, READY, ATTACHED, DELETE_PENDING, DELETED }

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "owner_id", nullable = false, updatable = false)
    private User owner;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "store_id", nullable = false, updatable = false)
    private Store store;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "review_id")
    private StoreReview review;
    private Integer sortOrder;
    @Column(nullable = false, unique = true, length = 200)
    private String temporaryKey;
    @Column(nullable = false, unique = true, length = 200)
    private String objectKey;
    @Column(nullable = false, length = 30)
    private String contentType;
    @Column(nullable = false)
    private long expectedSize;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20)
    private State state;
    @Column(nullable = false)
    private Instant createdAt;
    @Column(nullable = false)
    private Instant expiresAt;
    @Column(nullable = false)
    private Instant cleanupAt;
    @Column(nullable = false)
    private boolean temporaryCleaned;
    @Column(nullable = false)
    private int cleanupAttempts;
    private Integer width;
    private Integer height;

    public StoreReviewImage(User owner, Store store, String nonce, String contentType, long size, Instant now) {
        this.owner = owner;
        this.store = store;
        this.temporaryKey = "store-review-uploads/" + owner.getId() + "/" + nonce;
        this.objectKey = "store-review-images/" + store.getId() + "/" + owner.getId() + "/" + nonce + ".png";
        this.contentType = contentType;
        this.expectedSize = size;
        this.state = State.PENDING;
        this.createdAt = now;
        this.expiresAt = now.plusSeconds(86400);
        this.cleanupAt = expiresAt;
    }

    public void ready(int width, int height) {
        this.width = width;
        this.height = height;
        this.state = State.READY;
    }

    public void release() {
        this.review = null;
        this.sortOrder = null;
        this.state = State.READY;
    }

    public void attach(StoreReview review, int order) {
        this.review = review;
        this.sortOrder = order;
        this.state = State.ATTACHED;
    }

    public void retire(Instant now) {
        release();
        state = State.DELETE_PENDING;
        // 업로드 URL이 만료되기 전에 임시 키를 삭제하면 같은 URL로 재업로드할 수 있다.
        cleanupAt = now.isAfter(createdAt.plusSeconds(3600)) ? now : createdAt.plusSeconds(3600);
    }

    public void cleanedTemporary() { temporaryCleaned = true; }

    public void deleted() {
        release();
        state = State.DELETED;
    }

    public void retry(Instant now) {
        cleanupAttempts++;
        cleanupAt = now.plusSeconds(Math.min(86400, 60L << Math.min(cleanupAttempts, 10)));
    }
}
