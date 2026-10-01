// 구매자가 철회하려는 환불 식별자를 검증한다.
package com.kitschcatch.backend.domain.order.dto;
import jakarta.validation.constraints.*;
public record RefundWithdrawalRequest(@NotBlank @Pattern(regexp="REF-[A-Z0-9]{32}") String refundId) {}
