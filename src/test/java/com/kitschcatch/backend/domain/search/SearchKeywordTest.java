// 검색어 정규화와 공백·길이·제어문자 경계를 검증한다.
package com.kitschcatch.backend.domain.search;
import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;
class SearchKeywordTest {
    @Test void normalization() {
        assertThat(SearchKeyword.from("  Pokemon ").key()).isEqualTo(SearchKeyword.from("pokemon").key());
        assertThat(SearchKeyword.from("가").key()).isEqualTo(SearchKeyword.from("가").key());
        assertThat(SearchKeyword.from("가 나").key()).isNotEqualTo(SearchKeyword.from("가나").key());
        assertThat(SearchKeyword.from("100% !_").display()).isEqualTo("100% !_");
        assertThat(SearchKeyword.from("가".repeat(1000)).display()).hasSize(1000);
    }
    @Test void invalidInput() {
        for (String word:new String[]{null,"","   ","a\nb","가".repeat(1001)})
            assertThatThrownBy(()->SearchKeyword.from(word)).isInstanceOf(com.kitschcatch.backend.global.exception.BusinessException.class);
    }
}
