// 외부 PG 승인·취소 요청과 복구 결과를 시도 단위로 기록하는 엔티티
package com.kitschcatch.backend.domain.order.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

@Entity
@Table(
	name = "payment_attempts",
	uniqueConstraints = {
		@UniqueConstraint(name = "uk_payment_attempts_attempt_id", columnNames = "attempt_id"),
		@UniqueConstraint(name = "uk_payment_attempts_payment_sequence", columnNames = {"payment_id", "sequence_number"}),
		@UniqueConstraint(name = "uk_payment_attempts_idempotency_key", columnNames = "pg_idempotency_key"),
		@UniqueConstraint(name = "uk_payment_attempts_source_operation", columnNames = {"source_attempt_id", "operation"})
	},
	indexes = {
		@Index(name = "idx_payment_attempts_recovery", columnList = "attempt_status,next_check_at,id"),
		@Index(name = "idx_payment_attempts_payment_status", columnList = "payment_id,attempt_status")
	}
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PaymentAttempt {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "attempt_id", nullable = false, length = 50)
	private String attemptId;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "payment_id", nullable = false, foreignKey = @ForeignKey(name = "fk_payment_attempts_payment"))
	private Payment payment;

	@Column(name = "sequence_number", nullable = false)
	private int sequenceNumber;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 30)
	private PaymentAttemptOperation operation;

	@Enumerated(EnumType.STRING)
	@Column(name = "attempt_status", nullable = false, length = 30)
	private PaymentAttemptStatus attemptStatus;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "source_attempt_id", foreignKey = @ForeignKey(name = "fk_payment_attempts_source"))
	private PaymentAttempt sourceAttempt;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "approval_attempt_id", foreignKey = @ForeignKey(name = "fk_payment_attempts_approval"))
	private PaymentAttempt approvalAttempt;

	@Column(name = "pg_order_id", nullable = false, length = 64)
	private String pgOrderId;

	@Column(length = 255)
	private String paymentKey;

	@Column(nullable = false, updatable = false)
	private Long amount;

	@Column(length = 100)
	private String cancelReason;

	@Column(length = 128)
	private String requestHash;

	@Column(name = "pg_idempotency_key", nullable = false, length = 300)
	private String pgIdempotencyKey;

	@Column(length = 100)
	private String pgStatus;

	@Column(length = 100)
	private String pgErrorCode;

	@Column(length = 255)
	private String failureReason;

	@Column(nullable = false, updatable = false)
	private LocalDateTime requestedAt;

	private LocalDateTime completedAt;

	private LocalDateTime pgApprovedAt;

	private LocalDateTime pgCanceledAt;

	@Column(nullable = false)
	private LocalDateTime nextCheckAt;

	@Column(nullable = false)
	private int checkCount;

	@Column(length = 50)
	private String leaseToken;

	private LocalDateTime leaseUntil;

	@CreationTimestamp
	@Column(nullable = false, updatable = false)
	private LocalDateTime createdAt;

	@UpdateTimestamp
	@Column(nullable = false)
	private LocalDateTime updatedAt;

	@Builder
	private PaymentAttempt(
		String attemptId,
		Payment payment,
		int sequenceNumber,
		PaymentAttemptOperation operation,
		PaymentAttemptStatus attemptStatus,
		PaymentAttempt sourceAttempt,
		PaymentAttempt approvalAttempt,
		String pgOrderId,
		String paymentKey,
		Long amount,
		String cancelReason,
		String requestHash,
		String pgIdempotencyKey,
		String pgStatus,
		String pgErrorCode,
		String failureReason,
		LocalDateTime requestedAt,
		LocalDateTime completedAt,
		LocalDateTime pgApprovedAt,
		LocalDateTime pgCanceledAt,
		LocalDateTime nextCheckAt,
		int checkCount,
		String leaseToken,
		LocalDateTime leaseUntil
	) {
		this.attemptId = attemptId;
		this.payment = payment;
		this.sequenceNumber = sequenceNumber;
		this.operation = operation;
		this.attemptStatus = attemptStatus;
		this.sourceAttempt = sourceAttempt;
		this.approvalAttempt = approvalAttempt;
		this.pgOrderId = pgOrderId;
		this.paymentKey = paymentKey;
		this.amount = amount;
		this.cancelReason = cancelReason;
		this.requestHash = requestHash;
		this.pgIdempotencyKey = pgIdempotencyKey;
		this.pgStatus = pgStatus;
		this.pgErrorCode = pgErrorCode;
		this.failureReason = failureReason;
		this.requestedAt = requestedAt;
		this.completedAt = completedAt;
		this.pgApprovedAt = pgApprovedAt;
		this.pgCanceledAt = pgCanceledAt;
		this.nextCheckAt = nextCheckAt;
		this.checkCount = checkCount;
		this.leaseToken = leaseToken;
		this.leaseUntil = leaseUntil;
	}

	public boolean isActive() {
		return attemptStatus == PaymentAttemptStatus.PREPARED
			|| attemptStatus == PaymentAttemptStatus.PROCESSING
			|| attemptStatus == PaymentAttemptStatus.UNKNOWN;
	}

	public void markProcessing() {
		this.attemptStatus = PaymentAttemptStatus.PROCESSING;
	}

	public void markUnknown() {
		this.attemptStatus = PaymentAttemptStatus.UNKNOWN;
	}

	public void markSucceeded(String status, LocalDateTime approvedAt, LocalDateTime canceledAt) {
		this.attemptStatus = PaymentAttemptStatus.SUCCEEDED;
		this.pgStatus = status;
		this.pgApprovedAt = approvedAt;
		this.pgCanceledAt = canceledAt;
		this.completedAt = LocalDateTime.now();
	}

	public void markFailed(String errorCode, String reason) {
		this.attemptStatus = PaymentAttemptStatus.FAILED;
		this.pgErrorCode = errorCode;
		this.failureReason = reason;
		this.completedAt = LocalDateTime.now();
	}

	public void markExpired() {
		this.attemptStatus = PaymentAttemptStatus.EXPIRED;
		this.completedAt = LocalDateTime.now();
	}

	public void scheduleNextCheck(LocalDateTime nextCheckAt) {
		this.nextCheckAt = nextCheckAt;
		this.checkCount++;
	}

	public void claim(String leaseToken, LocalDateTime leaseUntil) {
		this.leaseToken = leaseToken;
		this.leaseUntil = leaseUntil;
	}
}
