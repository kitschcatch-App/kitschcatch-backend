package com.kitschcatch.backend.domain.post.dto;

import java.util.List;

public record CreatePostImageUploadUrlResponse(
	List<PostImageUploadUrlResponse> images
) {
}
