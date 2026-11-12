// 운영자 전용 환불 목록과 상세를 제공한다.
package com.kitschcatch.backend.domain.order.controller;

import com.kitschcatch.backend.domain.order.dto.RefundResponse;
import com.kitschcatch.backend.domain.order.entity.OrderRefund.Status;
import com.kitschcatch.backend.domain.order.service.AdminRefundService;
import com.kitschcatch.backend.global.response.ApiResponse;
import com.kitschcatch.backend.global.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/refunds")
@SecurityRequirement(name = "bearerAuth")
@RequiredArgsConstructor
public class AdminRefundController {
    private final AdminRefundService service;

    @GetMapping
    @Operation(summary = "운영자 환불 요청 목록", description = "기본 상태는 REQUESTED와 PROCESSING입니다. from은 포함, to는 제외합니다.")
    public ApiResponse<AdminRefundService.Result> list(@AuthenticationPrincipal AuthenticatedUser actor,
            @RequestParam(required = false) List<Status> status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @RequestParam(required = false) String orderId, @RequestParam(required = false) String refundId,
            @RequestParam(required = false) Boolean shipped,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(service.list(actor.userId(), status, from, to, orderId, refundId, shipped, page, size));
    }

    @GetMapping("/{refundId}")
    @Operation(summary = "운영자 환불 상세", description = "환불번호로 사진과 심사 결과를 조회합니다.")
    public ApiResponse<RefundResponse> detail(@AuthenticationPrincipal AuthenticatedUser actor, @PathVariable String refundId) {
        return ApiResponse.success(service.detail(actor.userId(), refundId));
    }
}
