// 토스페이먼츠 결제 API 응답 중 서비스 복구에 필요한 값을 표현하는 DTO
package com.kitschcatch.backend.domain.order.toss;

public record TossPaymentResponse(
	String paymentKey,
	String orderId,
	Long totalAmount,
	String status,
	Long balanceAmount,
	String approvedAt,
	String canceledAt,
	String lastTransactionKey
) {

	public TossPaymentResponse(String paymentKey, String orderId, Long totalAmount, String status) {
		this(paymentKey, orderId, totalAmount, status, null, null, null, null);
	}
}
