package com.kitschcatch.backend.domain.post.dto;

import com.kitschcatch.backend.domain.post.entity.ProductCategory;
import com.kitschcatch.backend.domain.post.entity.ProductCondition;
import com.kitschcatch.backend.domain.post.entity.ProductStatus;
import java.time.LocalDateTime;
import java.util.List;

public record PostResponse(
	Long id,
	Long sellerId,
	String sellerNickname,
	String title,
	String description,
	Long price,
	ProductCategory productCategory,
	ProductCondition productCondition,
	ProductStatus productStatus,
	List<PostImageResponse> images,
	LocalDateTime createdAt,
	LocalDateTime updatedAt
) {
}
