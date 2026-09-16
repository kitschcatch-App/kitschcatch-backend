// 외부 PG 결제 승인 토큰을 전달하는 DTO
package com.kitschcatch.backend.domain.order.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

@Schema(description = "결제 승인 요청")
public record ConfirmPaymentRequest(
	@Schema(description = "내부 결제 ID")
	@NotBlank(message = "결제 ID는 필수입니다.")
	String paymentId,

	@Schema(description = "토스페이먼츠 결제 키")
	@NotBlank(message = "토스 결제 키는 필수입니다.")
	String paymentKey,

	@Schema(description = "재시도 승인에 사용할 결제 시도 ID")
	String attemptId
) {

	public ConfirmPaymentRequest(String paymentId, String paymentKey) {
		this(paymentId, paymentKey, null);
	}
}
