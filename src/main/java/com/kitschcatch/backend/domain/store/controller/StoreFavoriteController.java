// 인증된 사용자의 관심 매장 등록·해제·내 목록 API와 입력 오류 계약을 제공한다.
package com.kitschcatch.backend.domain.store.controller;

import com.kitschcatch.backend.domain.store.dto.FavoriteStorePageResponse;
import com.kitschcatch.backend.domain.store.dto.StoreFavoriteResponse;
import com.kitschcatch.backend.domain.store.service.StoreFavoriteQueryService;
import com.kitschcatch.backend.domain.store.service.StoreFavoriteService;
import com.kitschcatch.backend.domain.store.service.StorePageQuery;
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
@Tag(name = "관심 매장", description = "로그인한 사용자의 관심 매장 관리")
@SecurityRequirement(name = "bearerAuth")
@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "AUTH_004: 인증 토큰 누락·오류")
public class StoreFavoriteController {
    private final StoreFavoriteService service;
    private final StoreFavoriteQueryService queries;

    public StoreFavoriteController(StoreFavoriteService service, StoreFavoriteQueryService queries) {
        this.service = service;
        this.queries = queries;
    }

    @PostMapping("/api/stores/{storeId}/favorites")
    @Operation(summary = "관심 매장 등록", description = "본문 없이 등록합니다. 이미 등록한 매장도 200이며 등록 시각을 바꾸지 않습니다. favoriteCount는 변경 후 조회 시점의 전체 사용자 등록 수입니다.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "관심 등록 완료 또는 이미 등록됨")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "COMMON_002: 매장 ID 형식·범위 오류")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "USER_001: 사용자 없음. STORE_001: 매장 없음")
    public ApiResponse<StoreFavoriteResponse> register(@AuthenticationPrincipal AuthenticatedUser user,
        @Parameter(schema = @Schema(minimum = "1")) @PathVariable long storeId) {
        return ApiResponse.success(service.register(user.userId(), storeId));
    }

    @DeleteMapping("/api/stores/{storeId}/favorites")
    @Operation(summary = "관심 매장 해제", description = "본문 없이 해제합니다. 관심 목록에 없는 매장도 200이며 다른 사용자의 등록은 유지됩니다.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "관심 해제 완료 또는 이미 해제됨")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "COMMON_002: 매장 ID 형식·범위 오류")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "USER_001: 사용자 없음. STORE_001: 매장 없음")
    public ApiResponse<StoreFavoriteResponse> remove(@AuthenticationPrincipal AuthenticatedUser user,
        @Parameter(schema = @Schema(minimum = "1")) @PathVariable long storeId) {
        return ApiResponse.success(service.remove(user.userId(), storeId));
    }

    @GetMapping("/api/users/me/favorite-stores")
    @Operation(summary = "내 관심 매장 목록 조회", description = "등록 시각·관심 관계 ID 역순입니다. 빈 목록도 200입니다. favorited는 항상 true입니다. 현재 위치 입력과 매장 이미지 데이터가 없어 distanceMeters·thumbnailUrl은 null입니다.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "내 관심 매장 페이지 조회 성공")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "COMMON_001: 페이지 조건 오류")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "USER_001: 사용자 없음")
    public ApiResponse<FavoriteStorePageResponse> list(@AuthenticationPrincipal AuthenticatedUser user,
        @Parameter(schema = @Schema(minimum = "0", maximum = "10000")) @RequestParam(defaultValue = "0") int page,
        @Parameter(schema = @Schema(minimum = "1", maximum = "100")) @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(queries.list(user.userId(), new StorePageQuery(page, size)));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ApiResponse<Void> invalidParameter(MethodArgumentTypeMismatchException exception) {
        return ApiResponse.fail(switch (exception.getName()) {
            case "page", "size" -> ErrorCode.INVALID_INPUT_VALUE;
            default -> ErrorCode.BAD_REQUEST;
        });
    }
}
