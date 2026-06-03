// 주문 생성 후 주문 번호와 초기 결제 상태를 반환하는 DTO
package com.kitschcatch.backend.domain.order.dto;

import com.kitschcatch.backend.domain.order.entity.PaymentStatus;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "주문 생성 응답")
public record CreateOrderResponse(
	@Schema(description = "주문 번호")
	String orderId,

	@Schema(description = "내부 결제 ID")
	String paymentId,

	@Schema(description = "초기 결제 상태")
	PaymentStatus status
) {
}
