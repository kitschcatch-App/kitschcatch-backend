// 토스페이먼츠 결제 API 응답 중 서비스 상태 변경에 필요한 값
package com.kitschcatch.backend.domain.order.toss;

public record TossPaymentResponse(
	String paymentKey,
	String orderId,
	Long totalAmount,
	String status
) {
}
