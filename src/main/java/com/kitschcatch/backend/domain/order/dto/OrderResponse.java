package com.kitschcatch.backend.domain.order.dto;

import com.kitschcatch.backend.domain.order.entity.OrderStatus;
import java.time.LocalDateTime;

public record OrderResponse(
	Long id,
	Long postId,
	Long userId,
	Long amount,
	OrderStatus orderStatus,
	LocalDateTime expiresAt,
	LocalDateTime createdAt,
	LocalDateTime updatedAt
) {
}
