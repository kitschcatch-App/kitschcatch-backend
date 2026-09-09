// 토스 외부 호출에 필요한 결제 선점 정보를 전달하는 DTO
package com.kitschcatch.backend.domain.order.service;

public record PaymentOperationContext(
	String orderId,
	Long amount,
	String paymentKey
) {
}
