// JWT 인증 사용자의 팔로우 변경과 사용자별 팔로워·팔로잉 목록 API를 제공한다.
package com.kitschcatch.backend.domain.follow.controller;

import com.kitschcatch.backend.domain.follow.dto.FollowPageResponse;
import com.kitschcatch.backend.domain.follow.dto.FollowResponse;
import com.kitschcatch.backend.domain.follow.service.FollowPageQuery;
import com.kitschcatch.backend.domain.follow.service.FollowService;
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
@RequestMapping("/api/users/{userId}")
@Tag(name = "사용자 팔로우", description = "사용자의 방향성 팔로우 관리와 공개 목록")
@SecurityRequirement(name = "bearerAuth")
@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "AUTH_004: 인증 토큰 누락·오류")
@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "USER_001: 요청자 또는 대상 사용자 없음")
public class FollowController {
    private final FollowService service;

    public FollowController(FollowService service) { this.service = service; }

    @PostMapping("/follow")
    @Operation(summary = "사용자 팔로우", description = "본문 없이 로그인한 사용자가 대상 사용자를 팔로우합니다. 반복 등록도 200이며 최초 등록 시각을 보존합니다.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "등록 완료 또는 이미 등록됨")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "COMMON_002: 자기 자신 또는 ID 형식·범위 오류")
    public ApiResponse<FollowResponse> follow(@AuthenticationPrincipal AuthenticatedUser actor,
        @Parameter(schema = @Schema(minimum = "1")) @PathVariable long userId) {
        return ApiResponse.success(service.change(actor.userId(), userId, true));
    }

    @DeleteMapping("/follow")
    @Operation(summary = "사용자 팔로우 해제", description = "로그인한 사용자의 대상 방향 관계만 해제합니다. 관계가 없어도 200입니다. 자기 자신은 400입니다.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "해제 완료 또는 이미 해제됨")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "COMMON_002: 자기 자신 또는 ID 형식·범위 오류")
    public ApiResponse<FollowResponse> unfollow(@AuthenticationPrincipal AuthenticatedUser actor,
        @Parameter(schema = @Schema(minimum = "1")) @PathVariable long userId) {
        return ApiResponse.success(service.change(actor.userId(), userId, false));
    }

    @GetMapping("/followers")
    @Operation(summary = "사용자의 팔로워 조회", description = "대상을 팔로우하는 사용자입니다. 등록 시각·관계 ID 역순이며 id·nickname·profileImageUrl만 반환합니다. 빈 페이지도 200입니다.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "공개 사용자 페이지 조회 성공")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "COMMON_001: 페이지 오류. COMMON_002: ID 오류")
    public ApiResponse<FollowPageResponse> followers(@AuthenticationPrincipal AuthenticatedUser actor,
        @Parameter(schema = @Schema(minimum = "1")) @PathVariable long userId,
        @Parameter(schema = @Schema(minimum = "0", maximum = "10000")) @RequestParam(defaultValue = "0") int page,
        @Parameter(schema = @Schema(minimum = "1", maximum = "100")) @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(service.list(actor.userId(), userId, new FollowPageQuery(page, size), true));
    }

    @GetMapping("/followings")
    @Operation(summary = "사용자의 팔로잉 조회", description = "대상이 팔로우하는 사용자입니다. 등록 시각·관계 ID 역순이며 id·nickname·profileImageUrl만 반환합니다. 빈 페이지도 200입니다.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "공개 사용자 페이지 조회 성공")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "COMMON_001: 페이지 오류. COMMON_002: ID 오류")
    public ApiResponse<FollowPageResponse> followings(@AuthenticationPrincipal AuthenticatedUser actor,
        @Parameter(schema = @Schema(minimum = "1")) @PathVariable long userId,
        @Parameter(schema = @Schema(minimum = "0", maximum = "10000")) @RequestParam(defaultValue = "0") int page,
        @Parameter(schema = @Schema(minimum = "1", maximum = "100")) @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(service.list(actor.userId(), userId, new FollowPageQuery(page, size), false));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ApiResponse<Void> invalidParameter(MethodArgumentTypeMismatchException exception) {
        return ApiResponse.fail(switch (exception.getName()) {
            case "page", "size" -> ErrorCode.INVALID_INPUT_VALUE;
            default -> ErrorCode.BAD_REQUEST;
        });
    }
}
