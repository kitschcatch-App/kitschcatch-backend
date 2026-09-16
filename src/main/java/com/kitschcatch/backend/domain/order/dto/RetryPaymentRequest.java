// 실패한 결제 시도를 지정하는 재시도 준비 요청 DTO
package com.kitschcatch.backend.domain.order.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

@Schema(description = "결제 재시도 준비 요청")
public record RetryPaymentRequest(
	@Schema(description = "재시도 원본이 되는 실패 시도 ID")
	@NotBlank(message = "실패한 결제 시도 ID는 필수입니다.")
	String failedAttemptId
) {
}
