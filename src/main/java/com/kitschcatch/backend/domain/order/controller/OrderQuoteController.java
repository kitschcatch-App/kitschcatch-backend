// 서버 견적 생성과 상품 배송비 조회·수정을 인증 사용자에게 제공한다.
package com.kitschcatch.backend.domain.order.controller;
import com.kitschcatch.backend.domain.order.entity.ShippingPolicy;
import com.kitschcatch.backend.domain.order.dto.PriceBreakdown;
import com.kitschcatch.backend.domain.order.service.OrderQuoteService;
import com.kitschcatch.backend.global.response.ApiResponse;
import com.kitschcatch.backend.global.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDateTime;
@RestController @SecurityRequirement(name="bearerAuth")
public class OrderQuoteController {
 private final OrderQuoteService service;
 public OrderQuoteController(OrderQuoteService service) {this.service=service;}
 public record QuoteRequest(@NotNull @Positive Long postId,@NotNull @Positive Long shippingAddressId) {}
 public record QuoteResponse(String quoteId,PriceBreakdown pricing,LocalDateTime expiresAt) {}
 public record ShippingRequest(@NotNull @Min(ShippingPolicy.AMOUNT) @Max(ShippingPolicy.AMOUNT) Long shippingAmount) {}
 public record ShippingResponse(Long shippingAmount) {}
 @PostMapping("/api/orders/quotes") @Operation(summary="주문 금액 견적",description="본인 배송지 필수. 5분 유효하며 상품을 예약하지 않습니다. 판매자 수수료 상품금액 5%, 구매자 0원.")
 public ApiResponse<QuoteResponse> quote(@AuthenticationPrincipal AuthenticatedUser u,@Valid @RequestBody QuoteRequest r) {return ApiResponse.created(service.quote(u.userId(),r));}
 @GetMapping("/api/posts/{postId}/shipping") @Operation(summary="상품 배송비 조회",description="새 거래의 배송비는 상품별 기존 설정과 관계없이 3,000원입니다.")
 public ApiResponse<ShippingResponse> shipping(@PathVariable long postId) {return ApiResponse.success(service.shipping(postId));}
 @PatchMapping("/api/posts/{postId}/shipping") @Operation(summary="상품 배송비 설정",description="판매자 전용. 배송비는 3,000원만 허용하며 진행 중인 주문이 있으면 변경할 수 없습니다.")
 public ApiResponse<ShippingResponse> shipping(@AuthenticationPrincipal AuthenticatedUser u,@PathVariable long postId,@Valid @RequestBody ShippingRequest r) {return ApiResponse.success(service.shipping(u.userId(),postId,r.shippingAmount()));}
}
