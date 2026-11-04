// 검색어 API의 저장 및 설정 계약을 정의한다.
package com.kitschcatch.backend.domain.search;

import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import com.kitschcatch.backend.global.security.AuthenticatedUser;
import com.kitschcatch.backend.global.response.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

@RestController
@RequestMapping("/api/search")
@Tag(name="검색어",description="검색 확정 기록과 최근·인기·방향성 연관어")
@SecurityRequirement(name="bearerAuth")
public class SearchController {
    private final SearchService service;
    public SearchController(SearchService service) { this.service=service; }
    public record RecordRequest(@NotBlank String keyword) {}
    @PostMapping("/records") @Operation(summary="확정 검색 기록",description="상품 검색 성공 후 한 번 호출합니다. 최근 20개를 보관하고 사용자별 같은 날 같은 검색어는 인기 집계에 한 번만 반영합니다.")
    public ApiResponse<SearchService.Recent> record(@AuthenticationPrincipal AuthenticatedUser user,@Valid @RequestBody RecordRequest request) { return ApiResponse.success(service.record(user.userId(),request.keyword())); }
    @GetMapping("/recent") @Operation(summary="내 최근 검색어",description="마지막 검색 시각·ID 역순, 없으면 items는 빈 배열입니다.")
    public ApiResponse<SearchService.Items<SearchService.Recent>> recent(@AuthenticationPrincipal AuthenticatedUser user) { return ApiResponse.success(service.recent(user.userId())); }
    @DeleteMapping("/recent/{recordId}") @Operation(summary="내 최근 검색어 개별 삭제",description="본인 기록만 삭제합니다. 없거나 타인 기록이어도 200입니다. 일별 집계는 유지합니다.")
    public ApiResponse<Void> delete(@AuthenticationPrincipal AuthenticatedUser user,@PathVariable Long recordId) { service.delete(user.userId(),recordId); return ApiResponse.success(null); }
    @DeleteMapping("/recent") @Operation(summary="내 최근 검색어 전체 삭제")
    public ApiResponse<Void> clear(@AuthenticationPrincipal AuthenticatedUser user) { service.delete(user.userId(),null); return ApiResponse.success(null); }
    @GetMapping("/popular") @Operation(summary="인기 검색어",description="기본 KST 오늘 포함 7일, 하루 사용자별 중복 제거, 최소 서로 다른 3명, 검색 수·사용자 수 역순 및 검색어 키 순. 부족하면 빈 배열입니다.")
    public ApiResponse<SearchService.Popular> popular(@AuthenticationPrincipal AuthenticatedUser user,@RequestParam(defaultValue="10") int limit) { return ApiResponse.success(service.popular(user.userId(),limit)); }
    @GetMapping("/related") @Operation(summary="연관 검색어",description="검수한 활성 방향성 관계를 우선순위·대상 키 순으로 조회합니다. 데이터가 없으면 빈 배열이며 상품 존재를 보장하지 않습니다. limit은 1~20입니다.")
    public ApiResponse<SearchService.Related> related(@AuthenticationPrincipal AuthenticatedUser user,@RequestParam String keyword,@RequestParam(defaultValue="10") int limit) { return ApiResponse.success(service.related(user.userId(),keyword,limit)); }
}
