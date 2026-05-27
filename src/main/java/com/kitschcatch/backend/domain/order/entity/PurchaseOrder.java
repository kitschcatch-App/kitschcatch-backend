package com.kitschcatch.backend.domain.order.entity;

import com.kitschcatch.backend.domain.post.entity.Post;
import com.kitschcatch.backend.domain.user.entity.User;
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
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

@Entity
@Table(name = "orders")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PurchaseOrder {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "user_id", nullable = false, foreignKey = @ForeignKey(name = "fk_orders_user"))
	private User user;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "post_id", nullable = false, foreignKey = @ForeignKey(name = "fk_orders_post"))
	private Post post;

	@Column(nullable = false)
	private Long amount;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 30)
	private OrderStatus orderStatus;

	@Column(nullable = false)
	private LocalDateTime expiresAt;

	@CreationTimestamp
	@Column(nullable = false, updatable = false)
	private LocalDateTime createdAt;

	@UpdateTimestamp
	@Column(nullable = false)
	private LocalDateTime updatedAt;

	@Builder
	private PurchaseOrder(
		User user,
		Post post,
		Long amount,
		OrderStatus orderStatus,
		LocalDateTime expiresAt
	) {
		this.user = user;
		this.post = post;
		this.amount = amount;
		this.orderStatus = orderStatus;
		this.expiresAt = expiresAt;
	}

	public static PurchaseOrder pending(User user, Post post, Long amount, LocalDateTime expiresAt) {
		return PurchaseOrder.builder()
			.user(user)
			.post(post)
			.amount(amount)
			.orderStatus(OrderStatus.PENDING)
			.expiresAt(expiresAt)
			.build();
	}

	public boolean isPending() {
		return orderStatus == OrderStatus.PENDING;
	}

	public boolean isExpired(LocalDateTime now) {
		return isPending() && !expiresAt.isAfter(now);
	}

	public void expire() {
		requireStatus(OrderStatus.PENDING);
		this.orderStatus = OrderStatus.EXPIRED;
	}

	public void markPaid() {
		requireStatus(OrderStatus.PENDING);
		this.orderStatus = OrderStatus.PAID;
	}

	public void cancel() {
		requireStatus(OrderStatus.PAID);
		this.orderStatus = OrderStatus.CANCELED;
	}

	private void requireStatus(OrderStatus expectedStatus) {
		if (orderStatus != expectedStatus) {
			throw new BusinessException(ErrorCode.ORDER_INVALID_STATE);
		}
	}
}
