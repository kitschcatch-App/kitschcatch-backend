package com.kitschcatch.backend.domain.payment.dto;

import com.kitschcatch.backend.domain.payment.entity.PaymentMethod;
import jakarta.validation.constraints.NotNull;

public record CreatePaymentRequest(
	@NotNull
	Long orderId,

	@NotNull
	PaymentMethod method
) {
}
