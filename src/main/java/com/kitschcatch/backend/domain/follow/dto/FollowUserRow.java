// 목록 조회에 필요한 공개 필드만 데이터베이스에서 선택한다.
package com.kitschcatch.backend.domain.follow.dto;

public record FollowUserRow(Long id, String nickname, String profileImageKey) {}
