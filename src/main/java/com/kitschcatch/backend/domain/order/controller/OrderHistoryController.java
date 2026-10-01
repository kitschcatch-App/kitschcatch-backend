// 거래 상세와 내 구매·판매 목록의 인증된 HTTP 조회 및 Swagger 계약을 제공한다.
package com.kitschcatch.backend.domain.order.controller;

import com.kitschcatch.backend.domain.order.dto.*;
import com.kitschcatch.backend.domain.order.service.OrderHistoryQuery;
import com.kitschcatch.backend.domain.order.service.OrderHistoryService;
import com.kitschcatch.backend.global.exception.ErrorCode;
import com.kitschcatch.backend.global.response.ApiResponse;
import com.kitschcatch.backend.global.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestController
@Tag(name = "거래 내역", description = "주문 시점 정보와 DB의 현재 주문·결제 상태 조회")
@SecurityRequirement(name = "bearerAuth")
@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "AUTH_004: 인증 누락·오류 또는 존재하지 않는 사용자")
public class OrderHistoryController {
    private final OrderHistoryService service;

    public OrderHistoryController(OrderHistoryService service) { this.service = service; }

    @GetMapping("/api/orders/{orderId}")
    @Operation(summary = "거래 상세 조회", description = "구매자·주문 당시 판매자만 조회합니다. 주문 번호는 주문 생성 응답의 orderId입니다. status는 주문 상태, payment.status는 결제 상태입니다. PG 조회·예약 만료·상태 변경은 하지 않습니다. 결제가 없는 과거 주문은 payment=null입니다.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "거래 상세 조회 성공")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "COMMON_002: 주문 번호 형식 오류")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "ORDER_004: 거래 당사자 아님")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "ORDER_001: 주문 없음")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "S3_001: 대표 이미지 URL 생성에 필요한 설정 누락")
    public ApiResponse<OrderDetailResponse> detail(@AuthenticationPrincipal AuthenticatedUser user,
        @Parameter(description = "ORD-로 시작하는 공개 주문 번호", schema = @Schema(pattern = "ORD-[A-Z0-9][A-Z0-9-]{0,45}", maxLength = 50))
        @PathVariable String orderId) {
        return ApiResponse.success(service.detail(user.userId(), orderId));
    }

    @GetMapping("/api/users/me/purchase-orders")
    @Operation(summary = "내 구매 거래 내역 조회", description = "status는 주문 상태 필터이며 생략하면 전체입니다. 주문 생성 시각·내부 ID 역순, 빈 목록도 200입니다. paymentStatus=null은 결제 행이 없는 주문입니다.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "구매 내역 페이지 조회 성공")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "COMMON_001: 상태 또는 페이지 조건 오류")
    public ApiResponse<OrderHistoryPageResponse<PurchaseOrderSummaryResponse>> purchases(
        @AuthenticationPrincipal AuthenticatedUser user,
        @Parameter(schema = @Schema(allowableValues = {"PENDING", "PAID", "CANCELED", "REFUNDED", "PURCHASE_CONFIRMED"})) @RequestParam(required = false) String status,
        @Parameter(schema = @Schema(minimum = "0", maximum = "10000")) @RequestParam(defaultValue = "0") int page,
        @Parameter(schema = @Schema(minimum = "1", maximum = "100")) @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(service.purchases(user.userId(), OrderHistoryQuery.from(status, page, size)));
    }

    @GetMapping("/api/users/me/sale-orders")
    @Operation(summary = "내 판매 거래 내역 조회", description = "주문 당시 판매자 ID 기준이며 구매자 닉네임도 주문 시점 값입니다. status는 주문 상태 필터입니다. 주문 생성 시각·내부 ID 역순, 빈 목록도 200입니다.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "판매 내역 페이지 조회 성공")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "COMMON_001: 상태 또는 페이지 조건 오류")
    public ApiResponse<OrderHistoryPageResponse<SaleOrderSummaryResponse>> sales(
        @AuthenticationPrincipal AuthenticatedUser user,
        @Parameter(schema = @Schema(allowableValues = {"PENDING", "PAID", "CANCELED", "REFUNDED", "PURCHASE_CONFIRMED"})) @RequestParam(required = false) String status,
        @Parameter(schema = @Schema(minimum = "0", maximum = "10000")) @RequestParam(defaultValue = "0") int page,
        @Parameter(schema = @Schema(minimum = "1", maximum = "100")) @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(service.sales(user.userId(), OrderHistoryQuery.from(status, page, size)));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ApiResponse<Void> invalidParameter(MethodArgumentTypeMismatchException exception) {
        return ApiResponse.fail(ErrorCode.INVALID_INPUT_VALUE);
    }
}
