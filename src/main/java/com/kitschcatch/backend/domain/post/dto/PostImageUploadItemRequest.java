package com.kitschcatch.backend.domain.post.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "판매 게시글 이미지 업로드 URL 발급 항목")
public record PostImageUploadItemRequest(
	@Schema(description = "원본 파일명")
	@NotBlank
	@Size(max = 255)
	String originalFileName,

	@Schema(description = "이미지 MIME 타입")
	@NotBlank
	@Size(max = 100)
	String contentType
) {
}
