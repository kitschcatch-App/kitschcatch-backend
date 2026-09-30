// 인증된 사용자를 위한 전국·주변·상세 매장 조회 API와 입력 오류를 제공한다.
package com.kitschcatch.backend.domain.store.controller;

import com.kitschcatch.backend.domain.store.dto.NearbyStorePageResponse;
import com.kitschcatch.backend.domain.store.dto.StoreDetailResponse;
import com.kitschcatch.backend.domain.store.dto.StorePageResponse;
import com.kitschcatch.backend.domain.store.service.NearbyStoreQuery;
import com.kitschcatch.backend.domain.store.service.NearbyStoreService;
import com.kitschcatch.backend.domain.store.service.StorePageQuery;
import com.kitschcatch.backend.domain.store.service.StoreService;
import com.kitschcatch.backend.global.exception.ErrorCode;
import com.kitschcatch.backend.global.security.AuthenticatedUser;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import com.kitschcatch.backend.global.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestController
@RequestMapping("/api/stores")
@Tag(name = "매장", description = "네이버 지도 SDK에서 사용하는 WGS84 좌표 기반 매장 조회")
@SecurityRequirement(name = "bearerAuth")
@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "AUTH_004: 인증 토큰 누락·오류")
public class StoreController {
    private final StoreService storeService;
    private final NearbyStoreService nearbyStoreService;

    public StoreController(StoreService storeService, NearbyStoreService nearbyStoreService) {
        this.storeService = storeService;
        this.nearbyStoreService = nearbyStoreService;
    }

    @GetMapping
    @Operation(summary = "전국 매장 목록 조회", description = "favorited는 요청 사용자의 관심 여부입니다. ID 오름차순입니다. 지역 생략 시 전국을 조회합니다. 빈 목록도 200이며 좌표는 WGS84 십진수입니다.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "페이지 조회 성공")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "COMMON_001: 지역 또는 페이지 조건 오류")
    public ApiResponse<StorePageResponse> list(
        @AuthenticationPrincipal AuthenticatedUser user,
        @Parameter(description = "17개 표준 지역 약칭", schema = @Schema(allowableValues = {
            "서울", "부산", "대구", "인천", "광주", "대전", "울산", "세종", "경기", "강원", "충북", "충남", "전북", "전남", "경북", "경남", "제주"}))
        @RequestParam(required = false) String region,
        @Parameter(schema = @Schema(minimum = "0", maximum = "10000")) @RequestParam(defaultValue = "0") int page,
        @Parameter(schema = @Schema(minimum = "1", maximum = "100")) @RequestParam(defaultValue = "20") int size
    ) {
        return ApiResponse.success(storeService.list(user.userId(), region, new StorePageQuery(page, size)));
    }

    @GetMapping("/nearby")
    @Operation(summary = "현재 위치 기준 주변 매장 조회", description = "favorited는 요청 사용자의 관심 여부입니다. 1·3·5km 구면 직선거리 이내를 실제 거리, ID 순으로 조회합니다. distanceMeters는 미터 반올림 값이며 경로 거리가 아닙니다. hasNext로 추가 페이지를 조회합니다. 기본 반경은 1km입니다.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "주변 매장 조회 성공")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "STORE_002: 좌표 누락·형식·범위 또는 반경 오류. COMMON_001: 페이지 조건 오류")
    public ApiResponse<NearbyStorePageResponse> nearby(
        @AuthenticationPrincipal AuthenticatedUser user,
        @Parameter(description = "WGS84 위도. NaN/Infinity 불가", schema = @Schema(minimum = "-90", maximum = "90"))
        @RequestParam double latitude,
        @Parameter(description = "WGS84 경도. NaN/Infinity 불가", schema = @Schema(minimum = "-180", maximum = "180"))
        @RequestParam double longitude,
        @Parameter(schema = @Schema(allowableValues = {"1", "3", "5"})) @RequestParam(defaultValue = "1") int radiusKm,
        @Parameter(schema = @Schema(minimum = "0", maximum = "10000")) @RequestParam(defaultValue = "0") int page,
        @Parameter(schema = @Schema(minimum = "1", maximum = "100")) @RequestParam(defaultValue = "20") int size
    ) {
        return ApiResponse.success(nearbyStoreService.nearby(user.userId(),
            new NearbyStoreQuery(latitude, longitude, radiusKm, new StorePageQuery(page, size))));
    }

    @GetMapping("/{storeId}")
    @Operation(summary = "매장 상세 및 운영 정보 조회", description = "favorited는 요청 사용자의 관심 여부입니다. 영업시간은 월요일부터 정렬합니다. 미등록은 빈 배열, 휴무일은 closed=true와 null 시각입니다. closeTime이 openTime 이하이면 다음 날 종료(같으면 24시간)입니다. phone 미등록은 null입니다.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "매장 상세 조회 성공")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "COMMON_002: 매장 ID 형식·범위 오류")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "STORE_001: 매장을 찾을 수 없음")
    public ApiResponse<StoreDetailResponse> detail(
        @AuthenticationPrincipal AuthenticatedUser user,
        @Parameter(schema = @Schema(minimum = "1")) @PathVariable long storeId
    ) {
        return ApiResponse.success(storeService.detail(user.userId(), storeId));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ApiResponse<Void> invalidParameter(MethodArgumentTypeMismatchException exception) {
        return ApiResponse.fail(parameterError(exception.getName()));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ApiResponse<Void> missingParameter(MissingServletRequestParameterException exception) {
        return ApiResponse.fail(parameterError(exception.getParameterName()));
    }

    private ErrorCode parameterError(String name) {
        return switch (name) {
            case "latitude", "longitude", "radiusKm" -> ErrorCode.STORE_QUERY_INVALID;
            case "page", "size", "region" -> ErrorCode.INVALID_INPUT_VALUE;
            default -> ErrorCode.BAD_REQUEST;
        };
    }
}
