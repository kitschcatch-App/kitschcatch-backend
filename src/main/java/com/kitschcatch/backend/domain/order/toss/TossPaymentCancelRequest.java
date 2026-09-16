// 토스페이먼츠 결제 취소 API에 전달하는 요청 DTO
package com.kitschcatch.backend.domain.order.toss;

import com.fasterxml.jackson.annotation.JsonIgnore;

public record TossPaymentCancelRequest(
	String paymentKey,
	String cancelReason,
	@JsonIgnore String idempotencyKey
) {

	public TossPaymentCancelRequest(String paymentKey, String cancelReason) {
		this(paymentKey, cancelReason, null);
	}
}
