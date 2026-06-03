// 토스페이먼츠 결제 승인 API에 전달하는 요청 DTO
package com.kitschcatch.backend.domain.order.toss;

public record TossPaymentConfirmRequest(
	String paymentKey,
	String orderId,
	Long amount
) {
}
