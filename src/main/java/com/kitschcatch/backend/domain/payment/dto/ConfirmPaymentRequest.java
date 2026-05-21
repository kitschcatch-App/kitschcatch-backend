package com.kitschcatch.backend.domain.payment.dto;

import jakarta.validation.constraints.NotBlank;

public record ConfirmPaymentRequest(
	@NotBlank
	String paymentKey
) {
}
