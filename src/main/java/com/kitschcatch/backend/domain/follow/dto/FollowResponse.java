// 요청 대상 사용자에 대한 인증 사용자의 변경 결과를 반환한다.
package com.kitschcatch.backend.domain.follow.dto;

public record FollowResponse(Long userId, boolean following) {}
