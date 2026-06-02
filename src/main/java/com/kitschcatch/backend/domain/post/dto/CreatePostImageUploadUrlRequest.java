package com.kitschcatch.backend.domain.post.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;

@Schema(description = "판매 게시글 이미지 업로드 URL 발급 요청")
public record CreatePostImageUploadUrlRequest(
	@Schema(description = "업로드 URL을 발급할 이미지 목록")
	@NotEmpty
	@Size(max = 10)
	List<@Valid PostImageUploadItemRequest> images
) {
}
