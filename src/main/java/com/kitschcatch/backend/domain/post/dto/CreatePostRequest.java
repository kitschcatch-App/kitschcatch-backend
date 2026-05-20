package com.kitschcatch.backend.domain.post.dto;

import com.kitschcatch.backend.domain.post.entity.ProductCategory;
import com.kitschcatch.backend.domain.post.entity.ProductCondition;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.util.List;

public record CreatePostRequest(
	@NotBlank
	@Size(max = 100)
	String title,

	@NotBlank
	@Size(max = 1000)
	String description,

	@NotNull
	@PositiveOrZero
	Long price,

	@NotNull
	ProductCategory productCategory,

	@NotNull
	ProductCondition productCondition,

	@NotEmpty
	@Size(max = 10)
	List<@NotBlank @Size(max = 512) String> imageKeys
) {
}
