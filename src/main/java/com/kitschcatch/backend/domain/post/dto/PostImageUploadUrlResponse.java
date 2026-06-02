package com.kitschcatch.backend.domain.post.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "판매 게시글 이미지 업로드 URL 응답 항목")
public record PostImageUploadUrlResponse(
	@Schema(description = "S3 업로드용 presigned URL")
	String uploadUrl,

	@Schema(description = "업로드 후 게시글 생성에 사용할 이미지 키")
	String imageKey,

	@Schema(description = "업로드 완료 후 접근할 이미지 URL")
	String imageUrl,

	@Schema(description = "업로드 URL 만료까지 남은 초")
	long expiresIn
) {
}
