// 정산 내부 실행과 거래 당사자의 상태 조회 API를 제공한다.
package com.kitschcatch.backend.domain.order.controller;
import com.kitschcatch.backend.domain.order.dto.SettlementResponse;
import com.kitschcatch.backend.domain.order.settlement.SettlementService;
import com.kitschcatch.backend.global.response.ApiResponse;
import com.kitschcatch.backend.global.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="400",description="COMMON_002: 주문 번호 오류.")
@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="401",description="AUTH_004: 인증 오류.")
@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="403",description="ORDER_004/SETTLEMENT_004: 조회 당사자 또는 내부 실행자 아님.")
@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="404",description="ORDER_001/SETTLEMENT_003/007: 주문·정산 또는 검증된 수취인 없음.")
@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="409",description="SETTLEMENT_001/008/009: 정산 상태·수취인·실행 시간·잔액 확인 필요.")
@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="502",description="SETTLEMENT_006: 지급 결과 미확정. 반복 실행 시 기존 지급 조회.")
@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="503",description="SETTLEMENT_005: 실제 지급 제공자 또는 수수료 미설정.")
@RestController @RequiredArgsConstructor
@RequestMapping("/api/orders/{orderId}/settlement")
@SecurityRequirement(name="bearerAuth")
public class SettlementController {
    private final SettlementService service;
    @PostMapping @Operation(summary="판매 대금 정산 실행",description="설정된 내부 실행자만 사용합니다. 지급 제공자·수수료가 미설정이면 503이며 지급 완료를 만들지 않습니다. 송금 POST 전 검사 실패는 같은 식별자로 재검사하고, POST 전송 이후 반복 요청은 기존 지급 식별자를 조회합니다.")
    public ApiResponse<SettlementResponse> execute(@AuthenticationPrincipal AuthenticatedUser user,@PathVariable String orderId) {
        return ApiResponse.success(service.execute(user.userId(),orderId));
    }
    @GetMapping @Operation(summary="정산 상태 조회",description="구매 확정 시 WAITING 정산이 생성됩니다. 당사자와 내부 실행자만 조회하며 외부 지급 요청은 하지 않습니다. 최초 실행 전 amount와 fee는 null입니다. settledAt은 서버의 지급 완료 확인 시각입니다.")
    public ApiResponse<SettlementResponse> get(@AuthenticationPrincipal AuthenticatedUser user,@PathVariable String orderId) {
        return ApiResponse.success(service.get(user.userId(),orderId));
    }
}
