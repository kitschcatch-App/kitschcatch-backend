// 결제 조회와 상태 변경 결과를 반환하는 DTO
package com.kitschcatch.backend.domain.order.dto;

import com.kitschcatch.backend.domain.order.entity.PaymentStatus;
import com.kitschcatch.backend.domain.order.entity.PaymentOperation;
import com.kitschcatch.backend.domain.order.entity.PaymentRecoveryState;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

@Schema(description = "결제 응답")
public record PaymentResponse(
	@Schema(description = "내부 결제 ID")
	String paymentId,

	@Schema(description = "주문 번호")
	String orderId,

	@Schema(description = "결제 금액")
	Long amount,

	@Schema(description = "결제 상태")
	PaymentStatus status,

	@Schema(description = "결제 생성 시각")
	LocalDateTime createdAt,

	@Schema(description = "결제 승인 시각")
	LocalDateTime approvedAt,
	String attemptId,
	PaymentOperation processingOperation,
	PaymentRecoveryState recoveryState,
	boolean retryAllowed,
	LocalDateTime reservationExpiresAt,
	LocalDateTime lastVerifiedAt,
	String failureCode
) {

	public PaymentResponse(
		String paymentId,
		String orderId,
		Long amount,
		PaymentStatus status,
		LocalDateTime createdAt,
		LocalDateTime approvedAt
	) {
		this(paymentId, orderId, amount, status, createdAt, approvedAt, null,
			PaymentOperation.NONE, PaymentRecoveryState.NONE, false, null, null, null);
	}
}
