package com.kitschcatch.backend.domain.post.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "판매 게시글 이미지 업로드 URL 응답 항목")
public record PostImageUploadUrlResponse(
	@Schema(description = "S3 업로드용 presigned URL")
	String uploadUrl,

	@Schema(description = "업로드 후 게시글 생성에 사용할 이미지 키")
	String imageKey,

	@Schema(description = "업로드 완료 후 사용할 S3 GET 서명 URL. 발급 시점부터 기본 15분 유효하며 업로드 URL과 만료기간이 다릅니다.")
	String imageUrl,

	@Schema(description = "업로드 URL 만료까지 남은 초")
	long expiresIn
) {
}
