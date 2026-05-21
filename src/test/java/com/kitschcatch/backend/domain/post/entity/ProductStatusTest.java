package com.kitschcatch.backend.domain.post.entity;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ProductStatusTest {

	@Test
	@DisplayName("판매 게시글 상태는 노출 가능한 판매 흐름만 제공한다")
	void containsVisibleSalePostStatusesInOrder() {
		assertThat(ProductStatus.values())
			.extracting(Enum::name)
			.containsExactly(
				"ON_SALE",
				"RESERVED",
				"SOLD_OUT"
			);
	}
}
