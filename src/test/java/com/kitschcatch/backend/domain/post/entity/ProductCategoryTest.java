package com.kitschcatch.backend.domain.post.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class ProductCategoryTest {

	private final ObjectMapper objectMapper = new ObjectMapper();

	@Test
	@DisplayName("판매 게시글 카테고리는 기획된 항목만 순서대로 제공한다")
	void containsSalePostCategoriesInOrder() {
		assertThat(ProductCategory.values())
			.extracting(Enum::name)
			.containsExactly(
				"ANIME_MANGA",
				"GAME",
				"GOODS",
				"COSPLAY",
				"BOOK",
				"MUSIC_VIDEO",
				"ETC"
			);
	}

	@Test
	@DisplayName("판매 게시글 카테고리는 프론트 응답에서 한글로 직렬화된다")
	void serializesCategoryLabelsInKorean() throws Exception {
		Map<String, String> expectedLabels = new LinkedHashMap<>();
		expectedLabels.put("ANIME_MANGA", "애니/만화");
		expectedLabels.put("GAME", "게임");
		expectedLabels.put("GOODS", "굿즈");
		expectedLabels.put("COSPLAY", "코스프레");
		expectedLabels.put("BOOK", "서적");
		expectedLabels.put("MUSIC_VIDEO", "음반/영상");
		expectedLabels.put("ETC", "기타");

		for (Map.Entry<String, String> expectedLabel : expectedLabels.entrySet()) {
			ProductCategory category = ProductCategory.valueOf(expectedLabel.getKey());

			assertThat(objectMapper.writeValueAsString(category)).isEqualTo("\"" + expectedLabel.getValue() + "\"");
		}
	}
}
