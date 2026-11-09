// 추천 개수 제한과 기존 상품 응답 형식의 추천 목록을 반환한다.
package com.kitschcatch.backend.domain.post.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

public record PostRecommendationResponse(
    @Schema(description = "요청에 적용한 최대 추천 개수") int limit,
    @Schema(description = "추천 순서로 정렬한 상품 목록. 후보가 없으면 빈 배열") List<PostResponse> posts
) {}
