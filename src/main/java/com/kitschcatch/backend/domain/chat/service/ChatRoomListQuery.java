// 채팅 목록의 역할과 상품 ID 필터를 검증한다.
package com.kitschcatch.backend.domain.chat.service;

import com.kitschcatch.backend.global.exception.BusinessException;
import com.kitschcatch.backend.global.exception.ErrorCode;

public record ChatRoomListQuery(Role role, Long postId) {
    public enum Role { BUYER, SELLER }
    public ChatRoomListQuery {
        if (postId != null && postId < 1) throw new BusinessException(ErrorCode.INVALID_INPUT_VALUE);
    }
    public static ChatRoomListQuery from(String role, String postId) {
        try {
            return new ChatRoomListQuery(role == null ? null : Role.valueOf(role), postId == null ? null : Long.valueOf(postId));
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(ErrorCode.INVALID_INPUT_VALUE);
        }
    }
}
