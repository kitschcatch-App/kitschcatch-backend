// 팔로우 사용자 목록의 최소 공개 정보와 페이지 메타데이터를 반환한다.
package com.kitschcatch.backend.domain.follow.dto;

import java.util.List;

public record FollowPageResponse(List<FollowUser> content, int page, int size,
                                 long totalElements, int totalPages) {
    public record FollowUser(Long id, String nickname, String profileImageUrl) {}
}
