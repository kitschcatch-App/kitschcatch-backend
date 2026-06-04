package com.kitschcatch.backend.domain.chat.dto;

public record ChatImageUploadUrl(
        String uploadUrl,
        String objectKey,
        String imageUrl,
        long expiresInSeconds
) {
}