package com.kitschcatch.backend.domain.order.entity;

import com.kitschcatch.backend.domain.post.entity.Post;
import com.kitschcatch.backend.domain.user.entity.User;
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
	name = "orders",
	uniqueConstraints = @UniqueConstraint(name = "uk_orders_order_number", columnNames = "order_number"),
	indexes = @Index(name = "idx_orders_reservation_expiry", columnList = "order_status,reservation_expires_at")
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PurchaseOrder {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "order_number", nullable = false, length = 50)
	private String orderNumber;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "user_id", nullable = false, foreignKey = @ForeignKey(name = "fk_orders_user"))
	private User user;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "post_id", nullable = false, foreignKey = @ForeignKey(name = "fk_orders_post"))
	private Post post;

	@Column(nullable = false, updatable = false)
	private Long amount;

	@Column(nullable = false, updatable = false)
	private Long snapshotPostId;

	@Column(nullable = false, updatable = false, length = 100)
	private String postTitle;

	@Column(nullable = false, updatable = false)
	private Long sellerId;

	@Column(nullable = false, updatable = false, length = 50)
	private String sellerNickname;

	@Column(updatable = false)
	private LocalDateTime reservationExpiresAt;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 30)
	private PgProvider pgProvider;

	@Column(length = 255)
	private String pgPaymentKey;

	@Column(length = 255)
	private String pgTransactionId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 30)
	private OrderStatus orderStatus;

	@CreationTimestamp
	@Column(nullable = false, updatable = false)
	private LocalDateTime createdAt;

	@UpdateTimestamp
	@Column(nullable = false)
	private LocalDateTime updatedAt;

	@Builder
	private PurchaseOrder(
		String orderNumber,
		User user,
		Post post,
		Long amount,
		PgProvider pgProvider,
		String pgPaymentKey,
		String pgTransactionId,
		OrderStatus orderStatus,
		LocalDateTime reservationExpiresAt
	) {
		this.orderNumber = orderNumber;
		this.user = user;
		this.post = post;
		this.amount = amount;
		this.pgProvider = pgProvider;
		this.pgPaymentKey = pgPaymentKey;
		this.pgTransactionId = pgTransactionId;
		this.orderStatus = orderStatus;
		this.snapshotPostId = post.getId();
		this.postTitle = post.getTitle();
		this.sellerId = post.getUser().getId();
		this.sellerNickname = post.getUser().getNickname();
		this.reservationExpiresAt = reservationExpiresAt;
	}

	public boolean isReservationExpired(LocalDateTime now) {
		return reservationExpiresAt != null && !reservationExpiresAt.isAfter(now);
	}

	public void markPaid(String pgPaymentKey) {
		this.pgPaymentKey = pgPaymentKey;
		this.orderStatus = OrderStatus.PAID;
	}

	public void cancel() {
		this.orderStatus = OrderStatus.CANCELED;
	}
}
