// 구매자의 전액 환불 요청과 당사자 환불 조회 API를 제공한다.
package com.kitschcatch.backend.domain.order.controller;
import com.kitschcatch.backend.domain.order.dto.*;
import com.kitschcatch.backend.domain.order.service.RefundService;
import com.kitschcatch.backend.global.response.ApiResponse;
import com.kitschcatch.backend.global.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="400",description="COMMON_001/002: 입력 오류. REFUND_001: 전액과 다른 금액. PAYMENT_005: PG 요청 실패 후 상태 조회 필요.")
@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="401",description="AUTH_004: 인증 오류.")
@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="403",description="ORDER_004: 요청 구매자 또는 조회 당사자 아님.")
@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="404",description="ORDER_001/REFUND_003: 주문 또는 환불 없음.")
@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="409",description="ORDER_005/REFUND_002: 처리 불가 상태 또는 기존 환불과 충돌.")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/orders/{orderId}/refunds")
@SecurityRequirement(name="bearerAuth")
public class RefundController {
    private final RefundService service;
    @PostMapping @Operation(summary="전액 환불 요청",description="구매자만 요청할 수 있습니다. 배송 전에는 PG 취소로 연결합니다. 배송 후에는 REQUESTED로 접수하며 반품 확인·승인은 후속 기능입니다. 같은 요청은 기존 결과를 반환합니다.")
    public ApiResponse<RefundResponse> request(@AuthenticationPrincipal AuthenticatedUser user,@PathVariable String orderId,
        @Valid @RequestBody RefundRequest request) { return ApiResponse.success(service.request(user.userId(),orderId,request)); }
    @GetMapping @Operation(summary="환불 상태 조회",description="거래 당사자만 저장된 환불 상태를 조회합니다. PG 취소 결과가 확인돼야 COMPLETED가 됩니다.")
    public ApiResponse<RefundResponse> get(@AuthenticationPrincipal AuthenticatedUser user,@PathVariable String orderId) {
        return ApiResponse.success(service.get(user.userId(),orderId));
    }
}
