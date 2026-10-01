// 주문 취소 사유의 길이와 빈 입력을 검증한다.
package com.kitschcatch.backend.domain.order.dto;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
public record CancelOrderRequest(@NotBlank @Size(max = 200) String reason) {}
