// 최근 본 상품 ID와 추천 개수의 형식 및 범위를 검증한다.
package com.kitschcatch.backend.domain.post.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.annotation.JsonDeserialize;

@JsonDeserialize(using = PostRecommendationRequest.Deserializer.class)
public record PostRecommendationRequest(
    @Schema(description = "가장 최근에 본 상품부터 정렬한 ID 배열", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull @Size(max = 20) List<@NotNull @Positive Long> recentPostIds,
    @Schema(description = "최대 추천 개수", defaultValue = "10", minimum = "1", maximum = "20")
    @Min(1) @Max(20) int limit
) {
    public static class Deserializer extends ValueDeserializer<PostRecommendationRequest> {
        @Override
        public PostRecommendationRequest deserialize(JsonParser parser, DeserializationContext context) {
            JsonNode body = context.readTree(parser);
            if (!body.isObject()) {
                return context.reportInputMismatch(PostRecommendationRequest.class, "요청 본문은 JSON 객체여야 합니다.");
            }

            JsonNode limit = body.get("limit");
            if (limit != null && (!limit.isIntegralNumber() || !limit.canConvertToInt())) {
                return context.reportInputMismatch(PostRecommendationRequest.class, "추천 개수는 정수여야 합니다.");
            }
            int requestedLimit = limit == null ? 10 : limit.intValue();
            JsonNode ids = body.get("recentPostIds");
            if (ids == null || ids.isNull()) {
                return new PostRecommendationRequest(null, requestedLimit);
            }
            if (!ids.isArray()) {
                return context.reportInputMismatch(PostRecommendationRequest.class, "최근 본 상품 ID는 배열이어야 합니다.");
            }

            var recentPostIds = new ArrayList<Long>();
            for (JsonNode id : ids) {
                if (id.isNull()) {
                    recentPostIds.add(null);
                } else if (id.isIntegralNumber() && id.canConvertToLong()) {
                    recentPostIds.add(id.longValue());
                } else {
                    return context.reportInputMismatch(PostRecommendationRequest.class, "상품 ID는 정수여야 합니다.");
                }
            }
            return new PostRecommendationRequest(recentPostIds, requestedLimit);
        }
    }
}
