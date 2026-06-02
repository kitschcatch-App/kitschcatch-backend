package com.kitschcatch.backend.domain.post.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "판매 게시글 이미지 업로드 URL 발급 응답")
public record CreatePostImageUploadUrlResponse(
	@Schema(description = "이미지별 업로드 URL 정보")
	List<PostImageUploadUrlResponse> images
) {
}
