// 본인과 다른 판매자의 상품 목록 HTTP 계약을 제공한다.
package com.kitschcatch.backend.domain.post.controller;

import com.kitschcatch.backend.domain.post.dto.SellerPostPageResponse;
import com.kitschcatch.backend.domain.post.service.SellerPostQuery;
import com.kitschcatch.backend.domain.post.service.SellerPostService;
import com.kitschcatch.backend.global.response.ApiResponse;
import com.kitschcatch.backend.global.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@Tag(name = "판매자 상품 목록")
@SecurityRequirement(name = "bearerAuth")
public class SellerPostController {
    private final SellerPostService service;
    public SellerPostController(SellerPostService service) { this.service = service; }

    @GetMapping("/api/users/me/posts")
    @Operation(summary = "내 상품 목록", description = "삭제 상품을 제외합니다. status 생략 시 ON_SALE, RESERVED, SOLD_OUT 전체를 생성 시각·ID 역순으로 반환합니다. 빈 목록도 200입니다.")
    public ApiResponse<SellerPostPageResponse> mine(@AuthenticationPrincipal AuthenticatedUser user,
        @Parameter(schema = @Schema(allowableValues = {"ON_SALE", "RESERVED", "SOLD_OUT"})) @RequestParam(required = false) String status,
        @Parameter(schema = @Schema(minimum = "0", maximum = "10000")) @RequestParam(defaultValue = "0") int page,
        @Parameter(schema = @Schema(minimum = "1", maximum = "100")) @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(service.list(user.userId(), user.userId(), SellerPostQuery.from(status, page, size)));
    }

    @GetMapping("/api/users/{userId}/posts")
    @Operation(summary = "판매자 상품 목록", description = "기존 상품 조회와 동일하게 세 판매 상태를 공개합니다. 삭제 상품 제외, 생성 시각·ID 역순입니다. 없는 회원은 404, 빈 목록은 200입니다.")
    public ApiResponse<SellerPostPageResponse> seller(@AuthenticationPrincipal AuthenticatedUser user,
        @PathVariable long userId,
        @Parameter(schema = @Schema(allowableValues = {"ON_SALE", "RESERVED", "SOLD_OUT"})) @RequestParam(required = false) String status,
        @Parameter(schema = @Schema(minimum = "0", maximum = "10000")) @RequestParam(defaultValue = "0") int page,
        @Parameter(schema = @Schema(minimum = "1", maximum = "100")) @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(service.list(user.userId(), userId, SellerPostQuery.from(status, page, size)));
    }
}
