// 기기 등록 토큰과 지원 플랫폼의 입력 계약을 정의한다.
package com.kitschcatch.backend.domain.notification;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;

public record DeviceTokenRequest(
    @NotBlank
        @Size(max = 2048)
        @Pattern(regexp = "[A-Za-z0-9_:.\\-]+")
        @Schema(description = "FCM 등록 토큰. 로그에 기록하지 않습니다.")
        String token,
    @NotBlank @Pattern(regexp = "ANDROID|IOS|WEB") String platform) {}
