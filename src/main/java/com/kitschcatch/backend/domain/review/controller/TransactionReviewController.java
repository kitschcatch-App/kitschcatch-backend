// 인증된 거래 후기 작성·조회와 공개 사용자 신뢰 집계를 제공한다.
package com.kitschcatch.backend.domain.review.controller;

import com.kitschcatch.backend.domain.order.dto.OrderHistoryPageResponse;
import com.kitschcatch.backend.domain.review.dto.*;
import com.kitschcatch.backend.domain.review.service.TransactionReviewService;
import com.kitschcatch.backend.global.response.ApiResponse;
import com.kitschcatch.backend.global.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@Tag(name = "거래 후기", description = "구매 확정된 주문의 상대방 평가와 실제 신뢰 정보")
@SecurityRequirement(name = "bearerAuth")
public class TransactionReviewController {
    private final TransactionReviewService service;

    @PostMapping("/api/orders/{orderId}/reviews")
    @Operation(summary = "거래 후기 작성", description = "구매자 또는 주문 당시 판매자가 상대방에게 한 번 작성합니다. 평점은 정수 1~5, 본문은 1~1000자입니다. orderId는 ORD- 공개 주문 번호입니다. 중복은 409 REVIEW_002입니다.")
    public ApiResponse<ReviewResponse> create(@AuthenticationPrincipal AuthenticatedUser user,
        @PathVariable String orderId, @Valid @RequestBody CreateReviewRequest request) {
        return ApiResponse.created(service.create(user.userId(), orderId, request));
    }

    @GetMapping("/api/orders/{orderId}/reviews")
    @Operation(summary = "거래 후기 조회", description = "거래 당사자만 조회합니다. 최대 두 건을 작성 시각·ID 오름차순으로 반환합니다. 미작성 주문은 빈 배열입니다.")
    public ApiResponse<List<ReviewResponse>> forOrder(@AuthenticationPrincipal AuthenticatedUser user,
        @PathVariable String orderId) {
        return ApiResponse.success(service.forOrder(user.userId(), orderId));
    }

    @GetMapping("/api/users/{userId}/reviews")
    @Operation(summary = "사용자가 받은 후기 조회", description = "로그인한 사용자가 조회합니다. 작성 시각·ID 내림차순입니다. 주문 번호·연락처·결제·배송 정보는 반환하지 않습니다.")
    public ApiResponse<OrderHistoryPageResponse<ReviewResponse>> forUser(@AuthenticationPrincipal AuthenticatedUser user,
        @PathVariable long userId, @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(service.forUser(user.userId(), userId, page, size));
    }

    @GetMapping("/api/users/{userId}/trust-info")
    @Operation(summary = "사용자 신뢰 정보 조회", description = "구매 확정된 참여 주문 수, 받은 후기 수, 평균 평점만 반환합니다. 평균은 소수 둘째 자리 HALF_UP이며 후기가 없으면 null입니다. 임의 신뢰 점수는 없습니다.")
    public ApiResponse<TrustInfoResponse> trustInfo(@AuthenticationPrincipal AuthenticatedUser user,
        @PathVariable long userId) {
        return ApiResponse.success(service.trustInfo(user.userId(), userId));
    }
}
