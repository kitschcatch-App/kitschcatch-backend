package com.kitschcatch.backend.domain.payment.client;

public interface TossPaymentClient {

	TossPaymentResult confirmPayment(String paymentKey, String orderId, Long amount, String idempotencyKey);

	TossPaymentResult getPayment(String paymentKey);

	TossPaymentResult cancelPayment(String paymentKey, String cancelReason, String idempotencyKey);
}
