package com.kitschcatch.backend.domain.post.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "판매 게시글 이미지 응답")
public record PostImageResponse(
	@Schema(description = "이미지 저장 키")
	String imageKey,

	@Schema(description = "이미지 조회 URL")
	String imageUrl,

	@Schema(description = "이미지 정렬 순서")
	int sortOrder
) {
}
