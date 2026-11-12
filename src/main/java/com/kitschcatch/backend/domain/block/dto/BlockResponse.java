// 차단 변경 요청자가 직접 설정한 차단 상태를 반환한다.
package com.kitschcatch.backend.domain.block.dto;

public record BlockResponse(long userId, boolean blockedByMe) {}
