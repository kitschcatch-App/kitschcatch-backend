// 결제 생성 후 결제 식별자와 상태를 반환하는 DTO
package com.kitschcatch.backend.domain.order.dto;

import com.kitschcatch.backend.domain.order.entity.PaymentStatus;

public record CreatePaymentResponse(
	String paymentId,
	PaymentStatus status
) {
}
