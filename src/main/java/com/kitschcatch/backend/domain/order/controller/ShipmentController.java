// 배송 등록·수정·조회 HTTP 요청과 인증 사용자를 연결한다.
package com.kitschcatch.backend.domain.order.controller;
import com.kitschcatch.backend.domain.order.dto.*;
import com.kitschcatch.backend.domain.order.service.ShipmentService;
import com.kitschcatch.backend.global.response.ApiResponse;
import com.kitschcatch.backend.global.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="400",description="COMMON_001/002: 지원하지 않는 택배사·송장 또는 주문 번호 오류.")
@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="401",description="AUTH_004: 인증 오류.")
@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="403",description="ORDER_004/SHIPMENT_002: 당사자 또는 판매자 아님.")
@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="404",description="ORDER_001/SHIPMENT_001: 주문 또는 송장 없음.")
@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="409",description="ORDER_005/006: 처리 불가 상태 또는 다른 송장 중복 등록.")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/orders/{orderId}/shipment")
@SecurityRequirement(name="bearerAuth")
public class ShipmentController {
    private final ShipmentService service;
    @PostMapping @Operation(summary="배송 정보 등록",description="판매자만 결제된 주문에 등록합니다. 같은 송장 재등록은 기존 결과를 반환하고 다른 송장은 409입니다.")
    public ApiResponse<ShipmentResponse> create(@AuthenticationPrincipal AuthenticatedUser user,
        @PathVariable String orderId,@Valid @RequestBody ShipmentRequest request) {
        return ApiResponse.success(service.save(user.userId(),orderId,request,false));
    }
    @PatchMapping @Operation(summary="배송 정보 수정",description="판매자만 송장을 수정합니다. 구매 확정·환불 처리 중에는 변경할 수 없습니다.")
    public ApiResponse<ShipmentResponse> update(@AuthenticationPrincipal AuthenticatedUser user,
        @PathVariable String orderId,@Valid @RequestBody ShipmentRequest request) {
        return ApiResponse.success(service.save(user.userId(),orderId,request,true));
    }
    @GetMapping @Operation(summary="배송 정보 조회",description="거래 당사자에게 등록된 송장을 반환합니다. 배송사 추적 API를 호출하지 않습니다.")
    public ApiResponse<ShipmentResponse> get(@AuthenticationPrincipal AuthenticatedUser user,@PathVariable String orderId) {
        return ApiResponse.success(service.get(user.userId(),orderId));
    }
}
