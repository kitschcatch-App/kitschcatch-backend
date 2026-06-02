// 주문 생성 후 주문 번호와 초기 결제 상태를 반환하는 DTO
package com.kitschcatch.backend.domain.order.dto;

import com.kitschcatch.backend.domain.order.entity.PaymentStatus;

public record CreateOrderResponse(
	String orderId,
	String paymentId,
	PaymentStatus status
) {
}
