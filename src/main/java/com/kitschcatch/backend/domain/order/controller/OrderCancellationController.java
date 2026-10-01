// 구매자의 주문 취소 요청을 인증된 사용자 기준으로 처리한다.
package com.kitschcatch.backend.domain.order.controller;
import com.kitschcatch.backend.domain.order.dto.*;
import com.kitschcatch.backend.domain.order.service.OrderCancellationService;
import com.kitschcatch.backend.global.response.ApiResponse;
import com.kitschcatch.backend.global.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="400",description="COMMON_001/002: 입력 또는 주문 번호 오류.")
@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="401",description="AUTH_004: 인증 오류.")
@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="403",description="ORDER_004: 구매자 아님.")
@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="404",description="ORDER_001: 주문 없음.")
@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="409",description="ORDER_005/006: 처리 불가 상태 또는 요청 내용 충돌.")
@RestController
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
@RequestMapping("/api/orders")
public class OrderCancellationController {
    private final OrderCancellationService service;
    @PostMapping("/{orderId}/cancel")
    @Operation(summary = "주문 취소", description = "구매자가 배송 전 주문을 취소합니다. 미결제 예약은 즉시 해제하고 PG 결과가 불확실하면 200 응답에 processing=true를 반환합니다. 반복 요청은 기존 결과를 반환합니다.")
    public ApiResponse<CancelOrderResponse> cancel(@AuthenticationPrincipal AuthenticatedUser user,
        @PathVariable String orderId, @Valid @RequestBody CancelOrderRequest request) {
        return ApiResponse.success(service.cancel(user.userId(), orderId, request));
    }
}
