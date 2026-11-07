// 내 차단 목록에 필요한 공개 사용자 정보와 페이지 정보를 반환한다.
package com.kitschcatch.backend.domain.block.dto;

import java.time.Instant;
import java.util.List;

public record BlockPageResponse(
    List<BlockedUser> content, int page, int size, long totalElements, int totalPages
) {
    public record BlockedUser(long userId, String nickname, String profileImageUrl, Instant blockedAt) {}
}
