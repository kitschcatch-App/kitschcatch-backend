package com.kitschcatch.backend.domain.payment.entity;

import com.kitschcatch.backend.domain.order.entity.PgProvider;
import com.kitschcatch.backend.domain.order.entity.PurchaseOrder;
import com.kitschcatch.backend.global.exception.BusinessException;
import com.kitschcatch.backend.global.exception.ErrorCode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.springframework.util.StringUtils;

@Entity
@Table(name = "payments")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Payment {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "order_id", nullable = false, foreignKey = @ForeignKey(name = "fk_payments_order"))
	private PurchaseOrder order;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 30)
	private PgProvider pgProvider;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 30)
	private PaymentMethod method;

	@Column(nullable = false)
	private Long amount;

	@Column(nullable = false, length = 64, unique = true)
	private String pgOrderId;

	@Column(length = 255)
	private String pgPaymentKey;

	@Column(length = 255)
	private String pgTransactionId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 30)
	private PaymentStatus status;

	@CreationTimestamp
	@Column(nullable = false, updatable = false)
	private LocalDateTime createdAt;

	@UpdateTimestamp
	@Column(nullable = false)
	private LocalDateTime updatedAt;

	private Payment(PurchaseOrder order, PgProvider pgProvider, PaymentMethod method, Long amount) {
		this.order = order;
		this.pgProvider = pgProvider;
		this.method = method;
		this.amount = amount;
		this.status = PaymentStatus.READY;
		this.pgOrderId = "KC-PAY-" + UUID.randomUUID().toString().replace("-", "");
	}

	public static Payment ready(PurchaseOrder order, PgProvider pgProvider, PaymentMethod method, Long amount) {
		return new Payment(order, pgProvider, method, amount);
	}

	public void assignPgOrderId(String pgOrderId) {
		this.pgOrderId = pgOrderId;
	}

	public void request() {
		this.status = PaymentStatus.REQUESTED;
	}

	public void startConfirm(String pgPaymentKey) {
		requirePgPaymentKey(pgPaymentKey);
		this.pgPaymentKey = pgPaymentKey;
		this.status = PaymentStatus.CONFIRMING;
	}

	public void restoreRequested() {
		this.pgPaymentKey = null;
		this.status = PaymentStatus.REQUESTED;
	}

	public void approve(String pgPaymentKey, String pgTransactionId) {
		requirePgPaymentKey(pgPaymentKey);
		this.pgPaymentKey = pgPaymentKey;
		if (StringUtils.hasText(pgTransactionId)) {
			this.pgTransactionId = pgTransactionId;
		}
		this.status = PaymentStatus.APPROVED;
	}

	public void startCancel() {
		this.status = PaymentStatus.CANCELING;
	}

	public void restoreApproved() {
		this.status = PaymentStatus.APPROVED;
	}

	public void cancel(String pgTransactionId) {
		if (StringUtils.hasText(pgTransactionId)) {
			this.pgTransactionId = pgTransactionId;
		}
		this.status = PaymentStatus.CANCELED;
	}

	public void fail() {
		this.status = PaymentStatus.FAILED;
	}

	private void requirePgPaymentKey(String pgPaymentKey) {
		if (!StringUtils.hasText(pgPaymentKey)) {
			throw new BusinessException(ErrorCode.PAYMENT_INVALID_STATE);
		}
	}
}
