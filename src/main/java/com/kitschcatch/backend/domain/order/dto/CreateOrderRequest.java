// 판매 게시글 기준 주문 생성을 요청하는 DTO
package com.kitschcatch.backend.domain.order.dto;

import com.kitschcatch.backend.domain.order.entity.PaymentMethod;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

@Schema(description = "주문 생성 요청")
public record CreateOrderRequest(
	@Schema(description = "주문할 판매 게시글 ID")
	@NotNull(message = "판매 게시글 ID는 필수입니다.")
	Long postId,

	@Schema(description = "결제 금액")
	@NotNull(message = "결제 금액은 필수입니다.")
	@Positive(message = "결제 금액은 0보다 커야 합니다.")
	Long amount,

	@Schema(description = "결제 수단. 신용카드·체크카드만 지원합니다.", example = "CARD")
	@NotNull(message = "결제 수단은 필수입니다.")
	PaymentMethod paymentMethod,
    @Schema(description="본인 배송지 ID. 앱 전환 후 app.orders.shipping-address-required 설정으로 필수화합니다.")
    @Positive Long shippingAddressId,
    @jakarta.validation.constraints.Size(max=50)
    @Schema(description="서버 견적 ID. amount는 견적의 totalAmount와 같아야 합니다.") String quoteId
) {
 public CreateOrderRequest(Long postId,Long amount,PaymentMethod method,Long addressId) {this(postId,amount,method,addressId,null);}
 public CreateOrderRequest(Long postId,Long amount,PaymentMethod paymentMethod) { this(postId,amount,paymentMethod,null,null); }
}
