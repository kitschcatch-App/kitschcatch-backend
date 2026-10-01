// 판매자 상품 목록의 내용과 페이지 및 전체 건수를 반환한다.
package com.kitschcatch.backend.domain.post.dto;

import java.util.List;
import org.springframework.data.domain.Page;

public record SellerPostPageResponse(List<PostResponse> content, int page, int size, long totalElements, int totalPages) {
    public static SellerPostPageResponse from(Page<PostResponse> page) {
        return new SellerPostPageResponse(page.getContent(), page.getNumber(), page.getSize(),
            page.getTotalElements(), page.getTotalPages());
    }
}
