// 최근 본 상품을 기준으로 비슷한 판매 상품을 추천한다.
package com.kitschcatch.backend.domain.post.controller;

import com.kitschcatch.backend.domain.post.dto.PostRecommendationRequest;
import com.kitschcatch.backend.domain.post.dto.PostRecommendationResponse;
import com.kitschcatch.backend.domain.post.service.PostRecommendationService;
import com.kitschcatch.backend.global.response.ApiResponse;
import com.kitschcatch.backend.global.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/posts/recommendations")
@Tag(name = "판매 게시글")
@SecurityRequirement(name = "bearerAuth")
public class PostRecommendationController {
    private final PostRecommendationService recommendations;

    public PostRecommendationController(PostRecommendationService recommendations) {
        this.recommendations = recommendations;
    }

    @PostMapping("/recently-viewed")
    @Operation(summary = "최근 본 상품과 비슷한 상품 추천",
        description = "최신순 최근 본 상품 ID를 최대 20개 받습니다. 최근 본 카테고리, 가격 차이, 등록 시각과 ID 순으로 추천합니다. 이미 본 상품, 본인 상품, 삭제·탈퇴·양방향 차단 상품은 추천에서 제외합니다. 결과가 없으면 빈 배열을 반환합니다.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "추천 조회 성공")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "COMMON_001: 입력 범위 오류. COMMON_002: 요청 형식 오류")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "AUTH_004: 유효한 액세스 토큰 필요")
    public ApiResponse<PostRecommendationResponse> recommend(
        @AuthenticationPrincipal AuthenticatedUser user,
        @Valid @RequestBody PostRecommendationRequest request
    ) {
        return ApiResponse.success(recommendations.recommend(user.userId(), request));
    }
}
