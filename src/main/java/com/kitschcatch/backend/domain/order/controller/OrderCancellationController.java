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
@RestController
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
@RequestMapping("/api/orders")
public class OrderCancellationController {
    private final OrderCancellationService service;
    @PostMapping("/{orderId}/cancel")
    @Operation(summary = "주문 취소", description = "구매자가 배송 전 주문을 취소합니다. 미결제 예약은 즉시 해제하고 PG 확인 중이면 processing=true를 반환합니다. 반복 요청은 기존 결과를 반환합니다.")
    public ApiResponse<CancelOrderResponse> cancel(@AuthenticationPrincipal AuthenticatedUser user,
        @PathVariable String orderId, @Valid @RequestBody CancelOrderRequest request) {
        return ApiResponse.success(service.cancel(user.userId(), orderId, request));
    }
}
