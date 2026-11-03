package com.kitschcatch.backend.domain.post.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "판매 게시글 이미지 응답")
public record PostImageResponse(
	@Schema(description = "이미지 저장 키")
	String imageKey,

	@Schema(description = "S3 GET 서명 URL. 기본 15분 유효하며 만료되면 상품 API를 다시 조회합니다.")
	String imageUrl,

	@Schema(description = "이미지 정렬 순서")
	int sortOrder
) {
}
