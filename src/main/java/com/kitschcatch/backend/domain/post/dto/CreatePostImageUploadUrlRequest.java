package com.kitschcatch.backend.domain.post.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;

public record CreatePostImageUploadUrlRequest(
	@NotEmpty
	@Size(max = 10)
	List<@Valid PostImageUploadItemRequest> images
) {
}
