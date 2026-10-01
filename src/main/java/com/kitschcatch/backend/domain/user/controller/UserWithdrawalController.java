// 인증된 본인 계정의 종료 요청만 받으며 외부 사용자 ID는 사용하지 않는다.
package com.kitschcatch.backend.domain.user.controller;

import com.kitschcatch.backend.domain.user.service.UserWithdrawalService;
import com.kitschcatch.backend.global.response.ApiResponse;
import com.kitschcatch.backend.global.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
@Tag(name = "사용자")
@SecurityRequirement(name = "bearerAuth")
public class UserWithdrawalController {
    private final UserWithdrawalService service;

    @DeleteMapping("/me")
    @Operation(summary = "회원 탈퇴", description = "진행 중이거나 결과가 미확정인 거래가 있으면 409를 반환합니다. 종료된 계정의 기존 토큰은 다음 요청부터 401을 반환합니다.")
    public ApiResponse<Void> withdraw(@AuthenticationPrincipal AuthenticatedUser user) {
        service.withdraw(user.userId());
        return ApiResponse.success(null);
    }
}
