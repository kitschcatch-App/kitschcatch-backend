package com.kitschcatch.backend.domain.payment.client;

public record TossPaymentResult(
	String paymentKey,
	String orderId,
	Long totalAmount,
	String status,
	String transactionKey
) {
}
