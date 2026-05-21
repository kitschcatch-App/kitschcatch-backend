package com.kitschcatch.backend.domain.post.dto;

public record PostImageResponse(
	String imageKey,
	String imageUrl,
	int sortOrder
) {
}
