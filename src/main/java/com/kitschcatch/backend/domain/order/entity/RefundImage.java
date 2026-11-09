// 반품 사진의 주문 소유권, 검증 결과, 첨부 순서와 정리 시점을 저장한다.
package com.kitschcatch.backend.domain.order.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "refund_images", uniqueConstraints =
    @UniqueConstraint(name = "uk_refund_images_sort_order", columnNames = {"refund_id", "sort_order"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RefundImage {
    public enum State { PENDING, READY, ATTACHED }

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, updatable = false)
    private long orderId;
    @Column(nullable = false, updatable = false)
    private long uploaderId;
    @Column(nullable = false, unique = true, updatable = false, length = 200)
    private String temporaryKey;
    @Column(nullable = false, unique = true, updatable = false, length = 200)
    private String objectKey;
    @Column(nullable = false, updatable = false, length = 30)
    private String contentType;
    @Column(nullable = false, updatable = false)
    private long expectedSize;
    @Column(nullable = false, updatable = false)
    private Instant createdAt;
    @Column(nullable = false, updatable = false)
    private Instant expiresAt;
    @Column(nullable = false)
    private Instant cleanupAt;
    @Column(nullable = false)
    private boolean temporaryCleaned;
    @Column(nullable = false)
    private int cleanupAttempts;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20)
    private State state;
    @Column(length = 50)
    private String refundId;
    private Integer sortOrder;
    private Integer width;
    private Integer height;
    @Column(length = 64)
    private String sha256;

    public RefundImage(long orderId, long uploaderId, String contentType, long expectedSize, Instant now) {
        this.orderId = orderId;
        this.uploaderId = uploaderId;
        this.contentType = contentType;
        this.expectedSize = expectedSize;
        String nonce = UUID.randomUUID().toString();
        this.temporaryKey = "refund-uploads/" + orderId + "/" + nonce;
        this.objectKey = "refund-images/" + orderId + "/" + nonce + ".png";
        this.state = State.PENDING;
        this.createdAt = now;
        this.expiresAt = now.plus(1, ChronoUnit.DAYS);
        this.cleanupAt = expiresAt;
    }

    public boolean expired(Instant now) {
        return !expiresAt.isAfter(now);
    }

    public void ready(int width, int height, String sha256) {
        this.width = width;
        this.height = height;
        this.sha256 = sha256;
        this.state = State.READY;
    }

    public void attach(String refundId, int sortOrder) {
        this.refundId = refundId;
        this.sortOrder = sortOrder;
        this.state = State.ATTACHED;
    }

    public void cleanedTemporary() {
        temporaryCleaned = true;
    }

    public void retryCleanup(Instant now) {
        cleanupAttempts++;
        cleanupAt = now.plusSeconds(Math.min(86400L, 60L << Math.min(cleanupAttempts, 10)));
    }
}
