package com.kitschcatch.backend.domain.post.dto;

import com.kitschcatch.backend.domain.post.entity.ProductCategory;
import com.kitschcatch.backend.domain.post.entity.ProductCondition;
import com.kitschcatch.backend.domain.post.entity.ProductStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.util.List;

@Schema(description = "판매 게시글 응답")
public record PostResponse(
	@Schema(description = "판매 게시글 ID")
	Long id,

	@Schema(description = "판매자 ID")
	Long sellerId,

	@Schema(description = "판매자 닉네임")
	String sellerNickname,

	@Schema(description = "판매 게시글 제목")
	String title,

	@Schema(description = "판매 게시글 설명")
	String description,

	@Schema(description = "판매 가격")
	Long price,

	@Schema(description = "상품 카테고리")
	ProductCategory productCategory,

	@Schema(description = "상품 상태")
	ProductCondition productCondition,

	@Schema(description = "판매 상태")
	ProductStatus productStatus,

	@Schema(description = "판매 게시글 이미지 목록")
	List<PostImageResponse> images,

	@Schema(description = "생성 시각")
	LocalDateTime createdAt,

	@Schema(description = "수정 시각")
	LocalDateTime updatedAt,

    @Schema(description = "현재 전체 관심 등록 수", minimum = "0")
    long favoriteCount,

    @Schema(description = "상품의 보관된 문의방 수. 빈 방과 참여자가 숨긴 방 포함", minimum = "0")
    long chatRoomCount
) {
}
