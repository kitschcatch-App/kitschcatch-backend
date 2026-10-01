// 전액 환불 요청 사유와 금액 형식을 검증한다.
package com.kitschcatch.backend.domain.order.dto;
import jakarta.validation.constraints.*;
public record RefundRequest(@NotBlank @Size(max=200) String reason,@NotNull @Positive Long amount) {}
