// 신고 심사 권한이 필요한 운영자 API를 제공한다.
package com.kitschcatch.backend.domain.report.review;

import static com.kitschcatch.backend.domain.report.review.AdminReportResponses.*;

import com.kitschcatch.backend.domain.report.ReportType;
import com.kitschcatch.backend.global.response.ApiResponse;
import com.kitschcatch.backend.global.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/reports")
@RequiredArgsConstructor
@Tag(name = "운영자 신고 심사", description = "활성 계정과 DB의 신고 심사 권한이 필요합니다.")
@SecurityRequirement(name = "bearerAuth")
@ApiResponses({
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "유효한 로그인 필요"),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "심사 권한 없음 또는 본인 관련 신고"),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "신고 또는 증거가 없거나 보관 기한 만료")
})
public class AdminReportController {
    private final AdminReportService service;
    private final ReportEvidenceImages images;

    @GetMapping
    @Operation(summary = "운영자 신고 목록", description = "기본 상태는 RECEIVED와 IN_REVIEW이며 접수 시각이 오래된 순으로 조회합니다. 접수 기간은 from 이상, to 미만입니다.")
    public ApiResponse<Page> list(@AuthenticationPrincipal AuthenticatedUser actor,
        @RequestParam(required = false) List<ReportReviewStatus> status,
        @RequestParam(required = false) ReportType targetType, @RequestParam(required = false) String reasonCode,
        @RequestParam(required = false) Long reporterId, @RequestParam(required = false) Long targetUserId,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
        @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(service.list(actor.userId(), status, targetType, reasonCode, reporterId, targetUserId,
            from, to, page, size));
    }

    @GetMapping("/{reportId}")
    @Operation(summary = "운영자 신고 상세", description = "접수 당시 대상 정보와 심사 상태를 조회합니다.")
    public ApiResponse<Detail> detail(@AuthenticationPrincipal AuthenticatedUser actor, @PathVariable long reportId) {
        return ApiResponse.ok(service.detail(actor.userId(), reportId));
    }

    @GetMapping("/{reportId}/evidence/messages")
    @Operation(summary = "선택된 채팅 증거 조회", description = "접수 당시 신고에 첨부된 최대 10개 메시지를 반환합니다.")
    public ApiResponse<List<Message>> messages(@AuthenticationPrincipal AuthenticatedUser actor, @PathVariable long reportId) {
        return ApiResponse.ok(service.messages(actor.userId(), reportId));
    }

    @GetMapping("/{reportId}/evidence/messages/{messageId}/image")
    @Operation(summary = "첨부 채팅 이미지 조회", description = "스냅샷에 첨부됐고 안전한 저장소 참조를 확인할 수 있는 이미지의 바이트를 반환합니다.")
    public ResponseEntity<byte[]> image(@AuthenticationPrincipal AuthenticatedUser actor, @PathVariable long reportId,
        @PathVariable long messageId) {
        var image = images.load(service.authorizeImage(actor.userId(), reportId, messageId));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(image.contentType()).body(image.bytes());
    }

    @GetMapping("/{reportId}/history")
    @Operation(summary = "신고 심사 이력", description = "접수 시각과 심사 상태 변경을 버전 순으로 반환합니다.")
    public ApiResponse<Timeline> history(@AuthenticationPrincipal AuthenticatedUser actor, @PathVariable long reportId) {
        return ApiResponse.ok(service.history(actor.userId(), reportId));
    }

    @PatchMapping("/{reportId}/status")
    @Operation(summary = "신고 심사 상태 변경", description = "버전 충돌과 재사용된 요청 ID의 내용 불일치는 409입니다. 최종 결과에는 사유가 필요합니다.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "버전·상태·요청 ID 충돌")
    public ApiResponse<Change> change(@AuthenticationPrincipal AuthenticatedUser actor, @PathVariable long reportId,
        @RequestBody ReportReviewRequest request) {
        return ApiResponse.ok(service.change(actor.userId(), reportId, request));
    }
}
