// 구매자의 수령 확인을 구매 확정 API로 제공한다.
package com.kitschcatch.backend.domain.order.controller;
import com.kitschcatch.backend.domain.order.dto.PurchaseConfirmationResponse;
import com.kitschcatch.backend.domain.order.service.PurchaseConfirmationService;
import com.kitschcatch.backend.global.response.ApiResponse;
import com.kitschcatch.backend.global.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="400",description="COMMON_002: 주문 번호 오류.")
@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="401",description="AUTH_004: 인증 오류.")
@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="403",description="ORDER_004: 구매자 아님.")
@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="404",description="ORDER_001: 주문 없음.")
@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="409",description="ORDER_005: 발송 전·환불 요청·불확실한 결제 등 확정 불가 상태.")
@RestController
@RequiredArgsConstructor
@SecurityRequirement(name="bearerAuth")
@RequestMapping("/api/orders")
public class PurchaseConfirmationController {
    private final PurchaseConfirmationService service;
    @PostMapping("/{orderId}/confirm-purchase")
    @Operation(summary="구매 확정",description="구매자가 발송된 상품의 수령을 확인합니다. 환불 요청 또는 불확실한 결제가 있으면 409입니다. 반복 요청은 최초 확정 시각을 반환합니다.")
    public ApiResponse<PurchaseConfirmationResponse> confirm(@AuthenticationPrincipal AuthenticatedUser user,@PathVariable String orderId) {
        return ApiResponse.success(service.confirm(user.userId(),orderId));
    }
}
