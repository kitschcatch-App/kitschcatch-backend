// 접수 스냅샷에서 심사 응답에 허용한 필드와 선택 메시지만 꺼낸다.
package com.kitschcatch.backend.domain.report.review;

import static com.kitschcatch.backend.domain.report.review.AdminReportResponses.*;

import com.kitschcatch.backend.domain.report.ReportType;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

final class ReportEvidenceSnapshot {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final AdminReportRepository.StoredReport report;
    private final JsonNode snapshot;

    ReportEvidenceSnapshot(AdminReportRepository.StoredReport report) {
        this.report = report;
        snapshot = JSON.readTree(report.snapshot());
    }

    TargetSnapshot target() {
        return switch (report.summary().targetType()) {
            case USER -> new UserSnapshot(id(snapshot, "id"), text(snapshot, "nickname"), text(snapshot, "username"),
                text(snapshot, "bio"), text(snapshot, "profile_image_key") != null);
            case POST -> new PostSnapshot(id(snapshot, "id"), id(snapshot, "user_id"), text(snapshot, "title"),
                text(snapshot, "description"), id(snapshot, "price"), text(snapshot, "created_at"),
                text(snapshot, "updated_at"), size(snapshot, "images"));
            case CHAT_ROOM -> new ChatRoomSnapshot(id(snapshot, "id"), id(snapshot, "post_id"),
                id(snapshot, "buyer_id"), id(snapshot, "seller_id"), messages().size());
            case STORE_REVIEW -> new StoreReviewSnapshot(id(snapshot, "id"), id(snapshot, "store_id"),
                id(snapshot, "author_id"), id(snapshot, "rating"), text(snapshot, "content"), id(snapshot, "version"),
                text(snapshot, "created_at"), text(snapshot, "updated_at"), size(snapshot, "images"));
        };
    }

    List<SelectedMessage> messages() {
        var summary = report.summary();
        if (summary.targetType() != ReportType.CHAT_ROOM || !Objects.equals(id(snapshot, "id"), summary.targetId())) {
            return List.of();
        }
        var selected = new HashSet<Long>();
        var ids = JSON.readTree(report.messageIds());
        if (!ids.isArray() || ids.size() > 10 || !snapshot.path("messages").isArray()) return List.of();
        ids.forEach(value -> {
            if (value.isIntegralNumber() && value.canConvertToLong() && value.longValue() > 0) selected.add(value.longValue());
        });
        var messages = new ArrayList<SelectedMessage>();
        for (var message : snapshot.path("messages")) {
            Long messageId = id(message, "id");
            Long senderId = id(message, "sender_id");
            if (!selected.contains(messageId) || senderId == null
                || (senderId != summary.reporterId() && senderId != summary.targetUserId())) continue;
            selected.remove(messageId);
            String type = text(message, "message_type");
            String path = "IMAGE".equals(type) ? text(message, "imageReferencePath") : null;
            messages.add(new SelectedMessage(new Message(messageId, senderId, type, text(message, "content"),
                text(message, "created_at"), path != null), path));
        }
        return List.copyOf(messages);
    }

    private static Long id(JsonNode node, String name) {
        var value = node.path(name);
        return value.isIntegralNumber() && value.canConvertToLong() ? value.longValue() : null;
    }

    private static String text(JsonNode node, String name) {
        var value = node.path(name);
        return value.isString() ? value.stringValue() : null;
    }

    private static int size(JsonNode node, String name) {
        var value = node.path(name);
        return value.isArray() ? value.size() : 0;
    }

    record SelectedMessage(Message message, String imagePath) {}
}
