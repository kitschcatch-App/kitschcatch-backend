// 토스 외부 호출에 필요한 결제 선점 정보를 전달하는 DTO
package com.kitschcatch.backend.domain.order.service;

public record PaymentOperationContext(
	String orderId,
	String pgOrderId,
	Long amount,
	String paymentKey,
	String attemptId,
	long stateVersion,
	String pgIdempotencyKey
) {

	public PaymentOperationContext(
		String orderId,
		String pgOrderId,
		Long amount,
		String paymentKey,
		String attemptId,
		long stateVersion
	) {
		this(orderId, pgOrderId, amount, paymentKey, attemptId, stateVersion, null);
	}

	public PaymentOperationContext(String orderId, Long amount, String paymentKey) {
		this(orderId, orderId, amount, paymentKey, null, 0L, null);
	}
}
