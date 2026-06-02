// 외부 PG 결제 승인 토큰을 전달하는 DTO
package com.kitschcatch.backend.domain.order.dto;

import jakarta.validation.constraints.NotBlank;

public record ConfirmPaymentRequest(
	@NotBlank(message = "결제 ID는 필수입니다.")
	String paymentId,

	@NotBlank(message = "결제 승인 토큰은 필수입니다.")
	String paymentToken
) {
}
