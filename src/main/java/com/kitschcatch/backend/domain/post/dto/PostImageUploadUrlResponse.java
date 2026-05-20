package com.kitschcatch.backend.domain.post.dto;

public record PostImageUploadUrlResponse(
	String uploadUrl,
	String imageKey,
	String imageUrl,
	long expiresIn
) {
}
