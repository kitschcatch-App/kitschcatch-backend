// 인증한 사용자의 신고 사유 조회와 접수 및 본인 내역 API를 제공한다.
package com.kitschcatch.backend.domain.report;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import com.kitschcatch.backend.global.security.AuthenticatedUser;
import com.kitschcatch.backend.global.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
@RestController
@RequiredArgsConstructor
@Tag(name="신고")
@SecurityRequirement(name="bearerAuth")
public class ReportController {
    private final ReportService service;
    private final ReportReasons reasons;
    @GetMapping("/api/reports/reasons")
    @Operation(summary="대상별 신고 사유 조회")
    public ApiResponse<List<ReportReasons.Reason>> reasons(@RequestParam ReportType targetType) {
        return ApiResponse.ok(reasons.list(targetType));
    }
    @PostMapping("/api/reports")
    @Operation(summary="신고 접수",description="첫 접수 201, 같은 요청 재전송 200, 다른 내용의 중복 409입니다. 신고는 차단·제재를 수행하지 않습니다. 채팅 메시지 최대 10개, 설명 최대 1000자이며 OTHER는 설명이 필수입니다.")
    public ApiResponse<ReportRepository.Summary> create(@AuthenticationPrincipal AuthenticatedUser actor,@RequestBody ReportRequest request) {
        var result=service.create(actor.userId(),request);
        return result.created() ? ApiResponse.created(result.report().summary()) : ApiResponse.ok(result.report().summary());
    }
    @GetMapping("/api/users/me/reports")
    @Operation(summary="내 신고 목록",description="page 0~10000, size 1~100. 최신 접수 시각·ID 순입니다.")
    public ApiResponse<ReportService.Page> list(@AuthenticationPrincipal AuthenticatedUser actor,
        @RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size) {
        return ApiResponse.ok(service.list(actor.userId(),page,size));
    }
    @GetMapping("/api/reports/{reportId}")
    @Operation(summary="내 신고 상세",description="타인의 신고와 없는 신고는 모두 404입니다. 내부 스냅샷은 반환하지 않습니다.")
    public ApiResponse<ReportRepository.Report> detail(@AuthenticationPrincipal AuthenticatedUser actor,@PathVariable long reportId) {
        return ApiResponse.ok(service.detail(actor.userId(),reportId));
    }
}
