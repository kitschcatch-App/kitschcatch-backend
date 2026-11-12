// 심사 변경 요청의 필드와 타입을 검사한다.
package com.kitschcatch.backend.domain.report.review;

import java.util.Set;
import java.util.UUID;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.annotation.JsonDeserialize;

@JsonDeserialize(using = ReportReviewRequest.Deserializer.class)
public record ReportReviewRequest(ReportReviewStatus status, String reason, long expectedVersion, UUID requestId) {
    public static class Deserializer extends ValueDeserializer<ReportReviewRequest> {
        private static final Set<String> FIELDS = Set.of("status", "reason", "expectedVersion", "requestId");

        @Override
        public ReportReviewRequest deserialize(JsonParser parser, DeserializationContext context) {
            var node = context.readTree(parser);
            var reason = node.path("reason");
            var version = node.path("expectedVersion");
            if (!node.isObject() || node.properties().stream().anyMatch(field -> !FIELDS.contains(field.getKey()))
                || !node.path("status").isString() || !node.path("requestId").isString()
                || !(reason.isMissingNode() || reason.isNull() || reason.isString())
                || !version.isIntegralNumber() || !version.canConvertToLong() || version.longValue() < 0) {
                return context.reportInputMismatch(ReportReviewRequest.class, "심사 요청의 필드 또는 타입이 올바르지 않습니다.");
            }
            try {
                String rawId = node.path("requestId").stringValue();
                UUID requestId = UUID.fromString(rawId);
                if (!requestId.toString().equalsIgnoreCase(rawId)) throw new IllegalArgumentException();
                return new ReportReviewRequest(ReportReviewStatus.valueOf(node.path("status").stringValue()),
                    reason.isString() ? reason.stringValue() : null, version.longValue(), requestId);
            } catch (IllegalArgumentException exception) {
                return context.reportInputMismatch(ReportReviewRequest.class, "심사 상태 또는 요청 ID가 올바르지 않습니다.");
            }
        }
    }
}
