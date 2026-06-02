// 판매 게시글 기준 주문 생성을 요청하는 DTO
package com.kitschcatch.backend.domain.order.dto;

import com.kitschcatch.backend.domain.order.entity.PaymentMethod;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record CreateOrderRequest(
	@NotNull(message = "판매 게시글 ID는 필수입니다.")
	Long postId,

	@NotNull(message = "결제 금액은 필수입니다.")
	@Positive(message = "결제 금액은 0보다 커야 합니다.")
	Long amount,

	@NotNull(message = "결제 수단은 필수입니다.")
	PaymentMethod paymentMethod
) {
}
