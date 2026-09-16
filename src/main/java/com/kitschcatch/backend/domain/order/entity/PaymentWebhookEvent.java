// Toss 결제 웹훅 수신과 재처리 상태를 기록하는 엔티티
package com.kitschcatch.backend.domain.order.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

@Entity
@Table(
	name = "payment_webhook_events",
	uniqueConstraints = @UniqueConstraint(name = "uk_payment_webhook_events_transmission_id", columnNames = "transmission_id"),
	indexes = @Index(name = "idx_payment_webhook_events_processing", columnList = "processing_status,next_process_at,id")
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PaymentWebhookEvent {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "transmission_id", nullable = false, length = 100)
	private String transmissionId;

	@Column(nullable = false, length = 50)
	private String eventType;

	@Column(nullable = false, length = 128)
	private String eventHash;

	@Column(length = 64)
	private String pgOrderId;

	@Column(length = 255)
	private String paymentKey;

	@Column(length = 30)
	private String pgStatus;

	private LocalDateTime pgCreatedAt;

	@Enumerated(EnumType.STRING)
	@Column(name = "processing_status", nullable = false, length = 30)
	private PaymentWebhookProcessingStatus processingStatus;

	@Column(nullable = false)
	private int receiveCount;

	@Column(nullable = false)
	private LocalDateTime nextProcessAt;

	private LocalDateTime processedAt;

	@Column(length = 255)
	private String failureReason;

	@Column(length = 50)
	private String leaseToken;

	private LocalDateTime leaseUntil;

	@CreationTimestamp
	@Column(nullable = false, updatable = false)
	private LocalDateTime receivedAt;

	@Builder
	private PaymentWebhookEvent(
		String transmissionId,
		String eventType,
		String eventHash,
		String pgOrderId,
		String paymentKey,
		String pgStatus,
		LocalDateTime pgCreatedAt,
		PaymentWebhookProcessingStatus processingStatus,
		int receiveCount,
		LocalDateTime nextProcessAt,
		LocalDateTime processedAt,
		String failureReason,
		String leaseToken,
		LocalDateTime leaseUntil
	) {
		this.transmissionId = transmissionId;
		this.eventType = eventType;
		this.eventHash = eventHash;
		this.pgOrderId = pgOrderId;
		this.paymentKey = paymentKey;
		this.pgStatus = pgStatus;
		this.pgCreatedAt = pgCreatedAt;
		this.processingStatus = processingStatus;
		this.receiveCount = receiveCount;
		this.nextProcessAt = nextProcessAt;
		this.processedAt = processedAt;
		this.failureReason = failureReason;
		this.leaseToken = leaseToken;
		this.leaseUntil = leaseUntil;
	}

	public void markProcessing(String leaseToken, LocalDateTime leaseUntil) {
		this.processingStatus = PaymentWebhookProcessingStatus.PROCESSING;
		this.leaseToken = leaseToken;
		this.leaseUntil = leaseUntil;
		this.receiveCount++;
	}

	public void markProcessed() {
		this.processingStatus = PaymentWebhookProcessingStatus.PROCESSED;
		this.processedAt = LocalDateTime.now();
		this.leaseToken = null;
		this.leaseUntil = null;
		this.failureReason = null;
	}

	public void scheduleRetry(LocalDateTime nextProcessAt, String failureReason) {
		this.processingStatus = PaymentWebhookProcessingStatus.RETRY_WAIT;
		this.nextProcessAt = nextProcessAt;
		this.failureReason = failureReason;
		this.leaseToken = null;
		this.leaseUntil = null;
	}

	public void ignore(String reason) {
		this.processingStatus = PaymentWebhookProcessingStatus.IGNORED;
		this.failureReason = reason;
		this.processedAt = LocalDateTime.now();
	}
}
