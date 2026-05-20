package com.kitschcatch.backend.domain.post.dto;

import com.kitschcatch.backend.domain.post.entity.ProductCategory;
import com.kitschcatch.backend.domain.post.entity.ProductCondition;
import com.kitschcatch.backend.domain.post.entity.ProductStatus;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.util.List;

public record UpdatePostRequest(
	@Size(max = 100)
	String title,

	@Size(max = 1000)
	String description,

	@PositiveOrZero
	Long price,

	ProductCategory productCategory,

	ProductCondition productCondition,

	ProductStatus productStatus,

	@Size(max = 10)
	List<@Size(max = 512) String> imageKeys
) {
}
