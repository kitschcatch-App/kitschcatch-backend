// 신고 입력의 타입과 허용 필드를 엄격히 검증한다.
package com.kitschcatch.backend.domain.report;
import java.util.*;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.*;
import tools.jackson.databind.annotation.JsonDeserialize;
@JsonDeserialize(using = ReportRequest.Deserializer.class)
public record ReportRequest(ReportType targetType, long targetId, String reasonCode,
                            String description, List<Long> messageIds) {
    public static class Deserializer extends ValueDeserializer<ReportRequest> {
        public ReportRequest deserialize(JsonParser parser, DeserializationContext context) {
            JsonNode n = context.readTree(parser);
            Set<String> fields = Set.of("targetType", "targetId", "reasonCode", "description", "messageIds");
            if (!n.isObject() || n.properties().stream().anyMatch(e -> !fields.contains(e.getKey())))
                return context.reportInputMismatch(ReportRequest.class, "지원하지 않는 신고 필드입니다.");
            JsonNode id = n.get("targetId");
            if (id == null || !id.isIntegralNumber() || !id.canConvertToLong() || id.longValue() <= 0)
                return context.reportInputMismatch(ReportRequest.class, "대상 ID는 양의 정수여야 합니다.");
            ReportType type;
            try { type = ReportType.valueOf(text(n.get("targetType"), context)); }
            catch (IllegalArgumentException | NullPointerException ex) {
                return context.reportInputMismatch(ReportRequest.class, "신고 대상 종류가 올바르지 않습니다.");
            }
            List<Long> ids = new ArrayList<>();
            JsonNode messages = n.get("messageIds");
            if (messages != null && !messages.isNull()) {
                if (!messages.isArray() || messages.size() > 10)
                    return context.reportInputMismatch(ReportRequest.class, "메시지는 최대 10개입니다.");
                for (JsonNode item : messages) {
                    if (!item.isIntegralNumber() || !item.canConvertToLong() || item.longValue() <= 0 || ids.contains(item.longValue()))
                        return context.reportInputMismatch(ReportRequest.class, "메시지 ID가 올바르지 않습니다.");
                    ids.add(item.longValue());
                }
                if (type != ReportType.CHAT_ROOM)
                    return context.reportInputMismatch(ReportRequest.class, "채팅 신고만 메시지를 연결할 수 있습니다.");
            }
            return new ReportRequest(type, id.longValue(), text(n.get("reasonCode"), context),
                text(n.get("description"), context), List.copyOf(ids));
        }
        private String text(JsonNode n, DeserializationContext c) {
            if (n == null || n.isNull()) return null;
            if (!n.isString()) return c.reportInputMismatch(ReportRequest.class, "문자열 필드가 올바르지 않습니다.");
            return n.stringValue();
        }
    }
}
