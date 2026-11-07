// 로그인한 사용자의 차단, 해제 및 내 차단 목록 API를 제공한다.
package com.kitschcatch.backend.domain.block.controller;

import com.kitschcatch.backend.domain.block.dto.BlockPageResponse;
import com.kitschcatch.backend.domain.block.dto.BlockResponse;
import com.kitschcatch.backend.domain.block.service.UserBlockService;
import com.kitschcatch.backend.global.exception.ErrorCode;
import com.kitschcatch.backend.global.response.ApiResponse;
import com.kitschcatch.backend.global.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
@Tag(name = "사용자 차단")
@SecurityRequirement(name = "bearerAuth")
public class UserBlockController {
    private final UserBlockService service;

    @PostMapping("/{userId}/block")
    @Operation(summary = "사용자 차단", description = "반복 요청은 200이며 최초 차단 시각을 유지합니다.")
    public ApiResponse<BlockResponse> block(@AuthenticationPrincipal AuthenticatedUser actor,
        @PathVariable long userId) {
        return ApiResponse.success(service.change(actor.userId(), userId, true));
    }

    @DeleteMapping("/{userId}/block")
    @Operation(summary = "사용자 차단 해제", description = "내 방향의 차단만 해제하며 기존 관계가 없어도 200입니다.")
    public ApiResponse<BlockResponse> unblock(@AuthenticationPrincipal AuthenticatedUser actor,
        @PathVariable long userId) {
        return ApiResponse.success(service.change(actor.userId(), userId, false));
    }

    @GetMapping("/me/blocks")
    @Operation(summary = "내 차단 목록", description = "최신 차단 순이며 page 0~10000, size 1~100을 허용합니다.")
    public ApiResponse<BlockPageResponse> list(@AuthenticationPrincipal AuthenticatedUser actor,
        @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(service.list(actor.userId(), page, size));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ApiResponse<Void> invalidParameter(MethodArgumentTypeMismatchException exception) {
        return ApiResponse.fail("userId".equals(exception.getName())
            ? ErrorCode.BAD_REQUEST : ErrorCode.INVALID_INPUT_VALUE);
    }
}
