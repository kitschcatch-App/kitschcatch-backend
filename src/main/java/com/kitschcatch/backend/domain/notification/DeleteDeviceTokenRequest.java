// 기기 해제 요청의 토큰 형식을 검증한다.
package com.kitschcatch.backend.domain.notification;

import jakarta.validation.constraints.*;

public record DeleteDeviceTokenRequest(
    @NotBlank @Size(max = 2048) @Pattern(regexp = "[A-Za-z0-9_:.\\-]+") String token) {}
