// 현재 환불 식별자와 운영자의 반품 검수 사유를 검증한다.
package com.kitschcatch.backend.domain.order.dto;
import jakarta.validation.constraints.*;
public record RefundDecisionRequest(@NotBlank @Pattern(regexp="REF-[A-Z0-9]{32}") String refundId,
    @NotBlank @Size(max=200) String reason, Boolean returnReceived) {}
