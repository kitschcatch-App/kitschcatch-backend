// 인증된 사용자의 매장 리뷰와 별점 집계 API를 제공한다.
package com.kitschcatch.backend.domain.store.controller;

import com.kitschcatch.backend.domain.store.dto.CreateStoreReviewRequest;
import com.kitschcatch.backend.domain.store.dto.StoreReviewResponse;
import com.kitschcatch.backend.domain.store.dto.StoreReviewSummary;
import com.kitschcatch.backend.domain.store.dto.UpdateStoreReviewRequest;
import com.kitschcatch.backend.domain.store.service.StorePageQuery;
import com.kitschcatch.backend.domain.store.service.StoreReviewService;
import com.kitschcatch.backend.global.response.ApiResponse;
import com.kitschcatch.backend.global.security.AuthenticatedUser;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@Tag(name = "매장 리뷰")
@SecurityRequirement(name = "bearerAuth")
public class StoreReviewController {
    private final StoreReviewService reviews;

    @PostMapping("/stores/{storeId}/reviews")
    @Operation(summary = "매장 리뷰 작성", description = "매장별 활성 리뷰 1개. 별점은 정수 1~5, 본문은 1~1000자입니다.")
    public ApiResponse<StoreReviewResponse> create(@AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable long storeId, @Valid @RequestBody CreateStoreReviewRequest request) {
        return ApiResponse.created(reviews.create(user.userId(), storeId, request));
    }

    @GetMapping("/stores/{storeId}/reviews")
    @Operation(summary = "매장 리뷰 목록", description = "공개 리뷰를 작성 시각과 ID 내림차순으로 반환합니다.")
    public ApiResponse<StoreReviewResponse.Page> list(@AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable long storeId, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(reviews.list(user.userId(), storeId, new StorePageQuery(page, size)));
    }

    @GetMapping("/stores/{storeId}/reviews/me")
    @Operation(summary = "매장에 작성한 내 리뷰", description = "삭제되지 않은 내 리뷰를 반환합니다. 미작성은 review: null입니다.")
    public ApiResponse<MyReview> mine(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable long storeId) {
        return ApiResponse.success(new MyReview(reviews.mine(user.userId(), storeId).orElse(null)));
    }

    @GetMapping("/store-reviews/{reviewId}")
    @Operation(summary = "공개 매장 리뷰 단건 조회")
    public ApiResponse<StoreReviewResponse> detail(@AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable long reviewId) {
        return ApiResponse.success(reviews.detail(user.userId(), reviewId));
    }

    @PatchMapping("/store-reviews/{reviewId}")
    @Operation(summary = "내 매장 리뷰 수정", description = "조회한 version과 변경할 별점, 본문 또는 imageIds를 보냅니다. imageIds 생략은 유지, 빈 배열은 전체 제거입니다.")
    public ApiResponse<StoreReviewResponse> update(@AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable long reviewId, @Valid @RequestBody UpdateStoreReviewRequest request) {
        return ApiResponse.success(reviews.update(user.userId(), reviewId, request));
    }

    @DeleteMapping("/store-reviews/{reviewId}")
    @Operation(summary = "내 매장 리뷰 삭제")
    public ApiResponse<Void> delete(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable long reviewId) {
        reviews.delete(user.userId(), reviewId);
        return ApiResponse.success(null);
    }

    @GetMapping("/stores/{storeId}/reviews/summary")
    @Operation(summary = "매장 리뷰 집계", description = "공개 리뷰 수, 평균, 1~5점별 개수를 반환합니다.")
    public ApiResponse<StoreReviewSummary> summary(@PathVariable long storeId) {
        return ApiResponse.success(reviews.summary(storeId));
    }

    public record MyReview(@JsonInclude(JsonInclude.Include.ALWAYS) StoreReviewResponse review) {}
}
