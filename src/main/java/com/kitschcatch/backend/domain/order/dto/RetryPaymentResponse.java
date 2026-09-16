// 결제 재시도에 사용할 PG 결제창 정보를 반환하는 DTO
package com.kitschcatch.backend.domain.order.dto;

import com.kitschcatch.backend.domain.order.entity.PaymentAttemptStatus;
import com.kitschcatch.backend.domain.order.entity.PaymentStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

@Schema(description = "결제 재시도 준비 응답")
public record RetryPaymentResponse(
	String paymentId,
	String orderId,
	String attemptId,
	String pgOrderId,
	Long amount,
	LocalDateTime reservationExpiresAt,
	PaymentStatus status,
	PaymentAttemptStatus attemptStatus,
	String nextAction
) {
}
