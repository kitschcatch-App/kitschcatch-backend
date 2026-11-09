// 운영자 화면에 제공하는 신고 요약, 상세, 증거와 처리 이력을 정의한다.
package com.kitschcatch.backend.domain.report.review;

import com.kitschcatch.backend.domain.report.ReportType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class AdminReportResponses {
    private AdminReportResponses() {}

    public record Summary(long reportId, ReportType targetType, long targetId, long reporterId,
        long targetUserId, String reasonCode, ReportReviewStatus status, Instant createdAt,
        Instant expiresAt, Instant updatedAt, long version) {}

    public record Page(List<Summary> content, int page, int size, long totalElements, long totalPages) {}

    public record Detail(Summary report, String description, TargetSnapshot targetSnapshot,
        Long reviewStartedBy, Instant reviewStartedAt, Long closedBy, Instant closedAt) {}

    @Schema(oneOf = {UserSnapshot.class, PostSnapshot.class, ChatRoomSnapshot.class, StoreReviewSnapshot.class})
    public sealed interface TargetSnapshot permits UserSnapshot, PostSnapshot, ChatRoomSnapshot, StoreReviewSnapshot {}

    public record UserSnapshot(Long id, String nickname, String username, String bio, boolean hasProfileImage)
        implements TargetSnapshot {}
    public record PostSnapshot(Long id, Long userId, String title, String description, Long price,
        String createdAt, String updatedAt, int imageCount) implements TargetSnapshot {}
    public record ChatRoomSnapshot(Long id, Long postId, Long buyerId, Long sellerId, int messageCount)
        implements TargetSnapshot {}
    public record StoreReviewSnapshot(Long id, Long storeId, Long authorId, Long rating, String content,
        Long version, String createdAt, String updatedAt, int imageCount) implements TargetSnapshot {}

    public record Message(long messageId, long senderId, String messageType, String content, String createdAt,
        boolean hasImageReference) {}

    public record History(long historyId, long reportId, ReportReviewStatus fromStatus, ReportReviewStatus toStatus,
        long actorId, String reason, Instant createdAt, long version, UUID requestId) {}
    public record Timeline(Instant receivedAt, List<History> changes) {}
    public record Change(Summary report, long historyId, boolean replayed) {}
}
