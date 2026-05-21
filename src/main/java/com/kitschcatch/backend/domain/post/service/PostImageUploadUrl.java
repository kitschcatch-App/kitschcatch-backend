package com.kitschcatch.backend.domain.post.service;

public record PostImageUploadUrl(
	String uploadUrl,
	String imageKey,
	String imageUrl,
	long expiresIn
) {
}
