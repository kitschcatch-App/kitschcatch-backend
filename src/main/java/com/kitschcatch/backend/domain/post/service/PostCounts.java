// 상품의 현재 관심 관계 수와 보관된 문의방 수를 표현한다.
package com.kitschcatch.backend.domain.post.service;

public record PostCounts(long favoriteCount, long chatRoomCount) {
    public static final PostCounts ZERO = new PostCounts(0, 0);
}
