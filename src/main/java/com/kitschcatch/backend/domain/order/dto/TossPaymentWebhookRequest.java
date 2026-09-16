// Toss 결제 상태 변경 웹훅의 수신 본문을 표현하는 DTO
package com.kitschcatch.backend.domain.order.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TossPaymentWebhookRequest(
	@NotBlank String eventType,
	@NotBlank String createdAt,
	@Valid @NotNull TossPaymentWebhookData data
) {

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record TossPaymentWebhookData(
		String paymentKey,
		String orderId,
		Long totalAmount,
		Long balanceAmount,
		String status
	) {
	}
}
