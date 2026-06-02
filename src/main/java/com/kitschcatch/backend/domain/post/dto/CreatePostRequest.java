package com.kitschcatch.backend.domain.post.dto;

import com.kitschcatch.backend.domain.post.entity.ProductCategory;
import com.kitschcatch.backend.domain.post.entity.ProductCondition;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.util.List;

@Schema(description = "판매 게시글 생성 요청")
public record CreatePostRequest(
	@Schema(description = "판매 게시글 제목")
	@NotBlank
	@Size(max = 100)
	String title,

	@Schema(description = "판매 게시글 설명")
	@NotBlank
	@Size(max = 1000)
	String description,

	@Schema(description = "판매 가격")
	@NotNull
	@PositiveOrZero
	Long price,

	@Schema(description = "상품 카테고리")
	@NotNull
	ProductCategory productCategory,

	@Schema(description = "상품 상태")
	@NotNull
	ProductCondition productCondition,

	@Schema(description = "업로드 완료된 이미지 키 목록")
	@NotEmpty
	@Size(max = 10)
	List<@NotBlank @Size(max = 512) String> imageKeys
) {
}
