// 운영자가 신고와 연결된 매장 리뷰를 조회하고 조치하는 API다.
package com.kitschcatch.backend.domain.store.moderation;

import com.kitschcatch.backend.global.response.ApiResponse;
import com.kitschcatch.backend.global.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/store-reviews")
@SecurityRequirement(name = "bearerAuth")
@RequiredArgsConstructor
public class AdminStoreReviewController {
    private final StoreReviewModeration moderation;

    @GetMapping
    @Operation(summary = "운영자 매장 리뷰 목록")
    public ApiResponse<List<StoreReviewModeration.Review>> list(@AuthenticationPrincipal AuthenticatedUser actor,
            @RequestParam(required = false) Long storeId, @RequestParam(required = false) Boolean hidden,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(moderation.list(actor.userId(), storeId, hidden, page, size));
    }

    @GetMapping("/{id}")
    @Operation(summary = "운영자 매장 리뷰 상세")
    public ApiResponse<StoreReviewModeration.Detail> detail(@AuthenticationPrincipal AuthenticatedUser actor,
            @PathVariable long id) {
        return ApiResponse.success(moderation.detail(actor.userId(), id));
    }

    @GetMapping("/{id}/moderation-history")
    @Operation(summary = "매장 리뷰 조치 이력")
    public ApiResponse<List<StoreReviewModeration.Entry>> history(@AuthenticationPrincipal AuthenticatedUser actor,
            @PathVariable long id, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(moderation.history(actor.userId(), id, page, size));
    }

    @PatchMapping("/{id}/moderation")
    @Operation(summary = "매장 리뷰 숨김 또는 숨김 해제")
    public ApiResponse<StoreReviewModeration.Result> change(@AuthenticationPrincipal AuthenticatedUser actor,
            @PathVariable long id, @Valid @RequestBody StoreReviewModeration.Command command) {
        return ApiResponse.success(moderation.change(actor.userId(), id, command));
    }
}
