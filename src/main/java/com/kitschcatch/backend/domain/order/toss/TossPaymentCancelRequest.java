// 토스페이먼츠 결제 취소 API에 전달하는 요청 DTO
package com.kitschcatch.backend.domain.order.toss;

public record TossPaymentCancelRequest(
	String paymentKey,
	String cancelReason
) {
}
