// 운영자가 지급 상태를 확인하고 조회 작업을 재예약하는 API다.
package com.kitschcatch.backend.domain.order.settlement;

import com.kitschcatch.backend.global.response.ApiResponse;
import com.kitschcatch.backend.global.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/settlements")
@SecurityRequirement(name = "bearerAuth")
@RequiredArgsConstructor
public class AdminSettlementController {
    private final SettlementSyncJobs jobs;

    @GetMapping
    @Operation(summary = "정산 결과 확인 목록")
    public ApiResponse<List<SettlementSyncJobs.Item>> list(@AuthenticationPrincipal AuthenticatedUser actor,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(jobs.list(actor.userId(), page, size));
    }

    @GetMapping("/{id}")
    @Operation(summary = "정산 결과 확인 상세")
    public ApiResponse<SettlementSyncJobs.Item> detail(@AuthenticationPrincipal AuthenticatedUser actor,
            @PathVariable String id) {
        return ApiResponse.success(jobs.detail(actor.userId(), id));
    }

    @PostMapping("/{id}/refresh")
    @Operation(summary = "기존 지급 결과 재조회 요청")
    public ApiResponse<SettlementSyncJobs.Item> refresh(@AuthenticationPrincipal AuthenticatedUser actor,
            @PathVariable String id) {
        return ApiResponse.accepted(jobs.refresh(actor.userId(), id));
    }
}
