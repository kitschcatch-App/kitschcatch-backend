// 결제 조회와 상태 변경 결과를 반환하는 DTO
package com.kitschcatch.backend.domain.order.dto;

import com.kitschcatch.backend.domain.order.entity.PaymentStatus;
import java.time.LocalDateTime;

public record PaymentResponse(
	String paymentId,
	String orderId,
	Long amount,
	PaymentStatus status,
	LocalDateTime createdAt,
	LocalDateTime approvedAt
) {
}
