package com.kitschcatch.backend.domain.post.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record PostImageUploadItemRequest(
	@NotBlank
	@Size(max = 255)
	String originalFileName,

	@NotBlank
	@Size(max = 100)
	String contentType
) {
}
