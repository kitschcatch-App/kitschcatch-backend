// 결제 생성 후 결제 식별자와 상태를 반환하는 DTO
package com.kitschcatch.backend.domain.order.dto;

import com.kitschcatch.backend.domain.order.entity.PaymentStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

@Schema(description = "결제 생성 응답")
public record CreatePaymentResponse(
	@Schema(description = "내부 결제 ID")
	String paymentId,

	@Schema(description = "결제 상태")
	PaymentStatus status,
	String attemptId,
	String pgOrderId,
	LocalDateTime reservationExpiresAt
) {

	public CreatePaymentResponse(String paymentId, PaymentStatus status) {
		this(paymentId, status, null, null, null);
	}
}
