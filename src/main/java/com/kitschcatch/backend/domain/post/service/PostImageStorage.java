package com.kitschcatch.backend.domain.post.service;

public interface PostImageStorage {

	PostImageUploadUrl createUploadUrl(Long userId, String originalFileName, String contentType);

	boolean isOwnedPostImageKey(Long userId, String objectKey);

	boolean exists(String objectKey);

	String imageUrl(String objectKey);
}
