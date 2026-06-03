package com.kitschcatch.backend.domain.post.dto;

import com.kitschcatch.backend.domain.post.entity.ProductCategory;
import com.kitschcatch.backend.domain.post.entity.ProductCondition;
import com.kitschcatch.backend.domain.post.entity.ProductStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.util.List;

@Schema(description = "판매 게시글 수정 요청")
public record UpdatePostRequest(
	@Schema(description = "수정할 판매 게시글 제목")
	@Size(max = 100)
	String title,

	@Schema(description = "수정할 판매 게시글 설명")
	@Size(max = 1000)
	String description,

	@Schema(description = "수정할 판매 가격")
	@PositiveOrZero
	Long price,

	@Schema(description = "수정할 상품 카테고리")
	ProductCategory productCategory,

	@Schema(description = "수정할 상품 상태")
	ProductCondition productCondition,

	@Schema(description = "수정할 판매 상태")
	ProductStatus productStatus,

	@Schema(description = "교체할 이미지 키 목록")
	@Size(max = 10)
	List<@Size(max = 512) String> imageKeys
) {
}
