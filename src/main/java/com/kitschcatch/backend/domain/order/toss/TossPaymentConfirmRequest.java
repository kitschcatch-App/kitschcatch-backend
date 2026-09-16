// 토스페이먼츠 결제 승인 API에 전달하는 요청 DTO
package com.kitschcatch.backend.domain.order.toss;

import com.fasterxml.jackson.annotation.JsonIgnore;

public record TossPaymentConfirmRequest(
	String paymentKey,
	String orderId,
	Long amount,
	@JsonIgnore String idempotencyKey
) {

	public TossPaymentConfirmRequest(String paymentKey, String orderId, Long amount) {
		this(paymentKey, orderId, amount, null);
	}
}
