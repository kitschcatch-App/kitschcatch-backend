// 주문에 연결된 결제 식별자와 상태를 저장하는 엔티티
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

@Entity
@Table(
	name = "payments",
	uniqueConstraints = @UniqueConstraint(name = "uk_payments_payment_id", columnNames = "payment_id")
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Payment {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "payment_id", nullable = false, length = 50)
	private String paymentId;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "order_id", nullable = false, foreignKey = @ForeignKey(name = "fk_payments_order"))
	private PurchaseOrder order;

	@Column(nullable = false)
	private Long amount;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 30)
	private PaymentMethod paymentMethod;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 30)
	private PaymentStatus paymentStatus;

	@Column(length = 255)
	private String paymentToken;

	@CreationTimestamp
	@Column(nullable = false, updatable = false)
	private LocalDateTime createdAt;

	private LocalDateTime approvedAt;

	private LocalDateTime canceledAt;

	@Builder
	private Payment(
		String paymentId,
		PurchaseOrder order,
		Long amount,
		PaymentMethod paymentMethod,
		PaymentStatus paymentStatus,
		String paymentToken
	) {
		this.paymentId = paymentId;
		this.order = order;
		this.amount = amount;
		this.paymentMethod = paymentMethod;
		this.paymentStatus = paymentStatus;
		this.paymentToken = paymentToken;
	}

	public void confirm(String paymentToken) {
		this.paymentToken = paymentToken;
		this.paymentStatus = PaymentStatus.SUCCESS;
		this.approvedAt = LocalDateTime.now();
		this.order.markPaid(paymentToken);
	}

	public void cancel() {
		this.paymentStatus = PaymentStatus.CANCELED;
		this.canceledAt = LocalDateTime.now();
		this.order.cancel();
	}
}
