package com.kitschcatch.backend.domain.payment.dto;

import com.kitschcatch.backend.domain.payment.entity.PaymentMethod;
import com.kitschcatch.backend.domain.payment.entity.PaymentStatus;
import java.time.LocalDateTime;

public record PaymentResponse(
	Long paymentId,
	Long orderId,
	String pgOrderId,
	Long amount,
	PaymentMethod method,
	PaymentStatus status,
	String orderName,
	String clientKey,
	String successUrl,
	String failUrl,
	LocalDateTime expiresAt,
	LocalDateTime createdAt,
	LocalDateTime updatedAt
) {
}
