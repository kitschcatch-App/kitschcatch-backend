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
	String lastTransactionKey,
	String method,
	Card card
) {

	public TossPaymentResponse(String paymentKey, String orderId, Long totalAmount, String status) {
		this(paymentKey, orderId, totalAmount, status, "CANCELED".equals(status) ? 0L : null, null, null, null);
	}

	public TossPaymentResponse(String paymentKey, String orderId, Long totalAmount, String status,
		Long balanceAmount, String approvedAt, String canceledAt, String lastTransactionKey) {
		this(paymentKey, orderId, totalAmount, status, balanceAmount, approvedAt, canceledAt, lastTransactionKey, null, null);
	}

	public boolean isSupportedCard() {
		if (!"카드".equals(method) && !"CARD".equals(method)) return false;
		String type = card == null ? null : card.cardType();
		return "신용".equals(type) || "체크".equals(type) || "CREDIT".equals(type) || "CHECK".equals(type);
	}

	public record Card(String cardType) {
	}
}
