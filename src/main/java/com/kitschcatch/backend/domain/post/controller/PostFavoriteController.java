// 인증된 사용자의 관심 상품 등록·해제·내 목록 API와 입력 오류 계약을 제공한다.
package com.kitschcatch.backend.domain.post.controller;

import com.kitschcatch.backend.domain.post.dto.FavoritePostPageResponse;
import com.kitschcatch.backend.domain.post.dto.PostFavoriteResponse;
import com.kitschcatch.backend.domain.post.service.PostFavoriteQueryService;
import com.kitschcatch.backend.domain.post.service.PostFavoriteService;
import com.kitschcatch.backend.domain.post.service.FavoritePostPageQuery;
import com.kitschcatch.backend.global.exception.ErrorCode;
import com.kitschcatch.backend.global.response.ApiResponse;
import com.kitschcatch.backend.global.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestController
@Tag(name = "관심 상품", description = "로그인한 사용자의 관심 상품 관리")
@SecurityRequirement(name = "bearerAuth")
@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "AUTH_004: 인증 토큰 누락·오류")
public class PostFavoriteController {
    private final PostFavoriteService service;
    private final PostFavoriteQueryService queries;

    public PostFavoriteController(PostFavoriteService service, PostFavoriteQueryService queries) {
        this.service = service;
        this.queries = queries;
    }

    @PostMapping("/api/posts/{postId}/favorites")
    @Operation(summary = "관심 상품 등록", description = "본문 없이 등록합니다. 이미 등록한 상품도 200이며 등록 시각을 바꾸지 않습니다. favoriteCount는 변경 후 조회 시점의 전체 사용자 등록 수입니다.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "관심 등록 완료 또는 이미 등록됨. 예약·판매 완료 상품도 등록 가능")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "COMMON_002: 상품 ID 형식·범위 오류")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "USER_001: 사용자 없음. POST_001: 상품 없음")
    public ApiResponse<PostFavoriteResponse> register(@AuthenticationPrincipal AuthenticatedUser user,
        @Parameter(schema = @Schema(minimum = "1")) @PathVariable long postId) {
        return ApiResponse.success(service.register(user.userId(), postId));
    }

    @DeleteMapping("/api/posts/{postId}/favorites")
    @Operation(summary = "관심 상품 해제", description = "본문 없이 해제합니다. 관심 목록에 없는 상품도 200이며 다른 사용자의 등록은 유지됩니다. 삭제된 상품도 해제할 수 있습니다.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "관심 해제 완료 또는 이미 해제됨")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "COMMON_002: 상품 ID 형식·범위 오류")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "USER_001: 사용자 없음. POST_001: 상품 없음")
    public ApiResponse<PostFavoriteResponse> remove(@AuthenticationPrincipal AuthenticatedUser user,
        @Parameter(schema = @Schema(minimum = "1")) @PathVariable long postId) {
        return ApiResponse.success(service.remove(user.userId(), postId));
    }

    @GetMapping("/api/users/me/favorite-posts")
    @Operation(summary = "내 관심 상품 목록 조회", description = "등록 시각·관심 관계 ID 역순입니다. 빈 목록도 200입니다. favorited는 항상 true입니다. 삭제 상품은 제외하고 판매 완료·예약 상품은 현재 productStatus를 표시합니다. 대표 이미지는 정렬상 첫 이미지이며 없으면 thumbnailUrl은 null입니다.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "내 관심 상품 페이지 조회 성공")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "COMMON_001: 페이지 조건 오류")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "USER_001: 사용자 없음")
    public ApiResponse<FavoritePostPageResponse> list(@AuthenticationPrincipal AuthenticatedUser user,
        @Parameter(schema = @Schema(minimum = "0", maximum = "10000")) @RequestParam(defaultValue = "0") int page,
        @Parameter(schema = @Schema(minimum = "1", maximum = "100")) @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(queries.list(user.userId(), new FavoritePostPageQuery(page, size)));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ApiResponse<Void> invalidParameter(MethodArgumentTypeMismatchException exception) {
        return ApiResponse.fail(switch (exception.getName()) {
            case "page", "size" -> ErrorCode.INVALID_INPUT_VALUE;
            default -> ErrorCode.BAD_REQUEST;
        });
    }
}
