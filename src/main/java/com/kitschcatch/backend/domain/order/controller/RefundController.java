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
@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="400",description="COMMON_001/002: 입력 오류. REFUND_005: 반품 수령 확인 누락. REFUND_001: 전액과 다른 금액. PAYMENT_005: PG 요청 실패 후 상태 조회 필요.")
@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="401",description="AUTH_004: 인증 오류.")
@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="403",description="ORDER_004: 요청 구매자 또는 조회 당사자 아님. REFUND_004: 검수 운영자 권한 없음.")
@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="404",description="ORDER_001/REFUND_003: 주문 또는 환불 없음.")
@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="409",description="ORDER_005/REFUND_002: 처리 불가 상태 또는 기존 환불과 충돌.")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/orders/{orderId}/refunds")
@SecurityRequirement(name="bearerAuth")
public class RefundController {
    private final RefundService service;
    @PostMapping @Operation(summary="전액 환불 요청",description="구매자만 요청할 수 있습니다. 배송 전에는 PG 취소로 연결합니다. 배송 후에는 REQUESTED로 접수하며 운영자가 반품 수령과 증빙을 확인해 승인합니다. 같은 요청은 기존 결과를 반환합니다.")
    public ApiResponse<RefundResponse> request(@AuthenticationPrincipal AuthenticatedUser user,@PathVariable String orderId,
        @Valid @RequestBody RefundRequest request) { return ApiResponse.success(service.request(user.userId(),orderId,request)); }
    @PostMapping("/approve") @Operation(summary="반품 수령 확인 및 전액 환불 승인",description="운영자 전용입니다. returnReceived=true와 검수 사유가 필요하며 승인 후 PG 전액 취소를 실행합니다.")
    public ApiResponse<RefundResponse> approve(@AuthenticationPrincipal AuthenticatedUser user,@PathVariable String orderId,
        @Valid @RequestBody RefundDecisionRequest request) { return ApiResponse.success(service.review(user.userId(),orderId,request,true)); }
    @PostMapping("/reject") @Operation(summary="반품 환불 거절",description="운영자 전용이며 현재 환불 ID와 거절 사유가 필요합니다.")
    public ApiResponse<RefundResponse> reject(@AuthenticationPrincipal AuthenticatedUser user,@PathVariable String orderId,
        @Valid @RequestBody RefundDecisionRequest request) { return ApiResponse.success(service.review(user.userId(),orderId,request,false)); }
    @PostMapping("/withdraw") @Operation(summary="반품 환불 철회",description="구매자만 REQUESTED 환불을 철회할 수 있습니다. 실물 반품 발송 전에 요청하세요.")
    public ApiResponse<RefundResponse> withdraw(@AuthenticationPrincipal AuthenticatedUser user,@PathVariable String orderId,
        @Valid @RequestBody RefundWithdrawalRequest request) { return ApiResponse.success(service.withdraw(user.userId(),orderId,request)); }
    @GetMapping @Operation(summary="환불 상태 조회",description="거래 당사자 또는 반품 운영자가 저장된 환불 상태를 조회합니다. PG 취소 결과가 확인돼야 COMPLETED가 됩니다.")
    public ApiResponse<RefundResponse> get(@AuthenticationPrincipal AuthenticatedUser user,@PathVariable String orderId) {
        return ApiResponse.success(service.get(user.userId(),orderId));
    }
}
