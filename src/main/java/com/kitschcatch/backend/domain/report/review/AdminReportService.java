// 운영자 권한과 신고의 범위를 검사하고 심사 상태와 이력을 함께 저장한다.
package com.kitschcatch.backend.domain.report.review;

import static com.kitschcatch.backend.domain.report.review.AdminReportResponses.*;
import static com.kitschcatch.backend.global.exception.ErrorCode.*;

import com.kitschcatch.backend.domain.report.ReportReasons;
import com.kitschcatch.backend.domain.report.ReportType;
import com.kitschcatch.backend.global.exception.BusinessException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
class AdminReportService {
    private final ReportReviewAccess access;
    private final AdminReportRepository reports;
    private final ReportReasons reasons;
    private final ReportEvidenceImages images;

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Page list(long actorId, List<ReportReviewStatus> statuses, ReportType targetType, String reasonCode,
        Long reporterId, Long targetUserId, Instant from, Instant to, int page, int size) {
        access.require(actorId);
        if (page < 0 || page > 10000 || size < 1 || size > 100
            || (reporterId != null && reporterId <= 0) || (targetUserId != null && targetUserId <= 0)
            || (from != null && to != null && !from.isBefore(to))
            || (statuses != null && (statuses.size() > 4 || statuses.stream().anyMatch(Objects::isNull)))) {
            throw new BusinessException(INVALID_INPUT_VALUE);
        }
        if (reasonCode != null && (targetType == null
            ? Arrays.stream(ReportType.values()).noneMatch(type -> reasons.allowed(type, reasonCode))
            : !reasons.allowed(targetType, reasonCode))) throw new BusinessException(REPORT_REASON_INVALID);

        var selected = statuses == null || statuses.isEmpty()
            ? List.of(ReportReviewStatus.RECEIVED, ReportReviewStatus.IN_REVIEW) : statuses;
        return reports.list(actorId, new AdminReportRepository.Filters(selected, targetType, reasonCode,
            reporterId, targetUserId, from, to), page, size);
    }

    @Transactional
    public Detail detail(long actorId, long reportId) {
        access.lock(actorId);
        var report = find(actorId, reportId, true);
        reports.recordAccess(reportId, actorId, AdminReportRepository.AccessType.DETAIL, null);
        return new Detail(report.summary(), report.description(), new ReportEvidenceSnapshot(report).target(),
            report.reviewStartedBy(), report.reviewStartedAt(), report.closedBy(), report.closedAt());
    }

    @Transactional
    public List<Message> messages(long actorId, long reportId) {
        access.lock(actorId);
        var report = chatReport(actorId, reportId);
        reports.recordAccess(reportId, actorId, AdminReportRepository.AccessType.MESSAGES, null);
        return new ReportEvidenceSnapshot(report).messages().stream().map(ReportEvidenceSnapshot.SelectedMessage::message).toList();
    }

    @Transactional
    public String authorizeImage(long actorId, long reportId, long messageId) {
        access.lock(actorId);
        if (messageId <= 0) throw new BusinessException(INVALID_INPUT_VALUE);
        var report = chatReport(actorId, reportId);
        var key = new ReportEvidenceSnapshot(report).messages().stream()
            .filter(entry -> entry.message().messageId() == messageId)
            .findFirst()
            .flatMap(entry -> images.key(entry.imagePath(), report.summary().targetId(), entry.message().senderId()))
            .orElseThrow(() -> new BusinessException(REPORT_EVIDENCE_NOT_FOUND));
        reports.recordAccess(reportId, actorId, AdminReportRepository.AccessType.IMAGE, messageId);
        return key;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Timeline history(long actorId, long reportId) {
        access.require(actorId);
        var report = find(actorId, reportId, false);
        return new Timeline(report.summary().createdAt(), reports.history(reportId));
    }

    @Transactional
    public Change change(long actorId, long reportId, ReportReviewRequest input) {
        access.lock(actorId);
        var request = normalize(input);
        validateId(reportId);
        var current = reports.lockForChange(reportId).orElseThrow(() -> new BusinessException(REPORT_NOT_FOUND));
        var now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        checkScope(current, actorId, now);

        var previous = reports.previous(actorId, request.requestId());
        if (previous.isPresent()) {
            var entry = previous.get();
            if (entry.reportId() != reportId || entry.toStatus() != request.status()
                || entry.version() - 1 != request.expectedVersion() || !Objects.equals(entry.reason(), request.reason())) {
                throw new BusinessException(REPORT_REVIEW_CONFLICT);
            }
            return new Change(current, entry.historyId(), true);
        }

        if (current.version() != request.expectedVersion() || !current.status().canMoveTo(request.status())) {
            throw new BusinessException(REPORT_REVIEW_CONFLICT);
        }
        var changed = reports.update(current, request.status(), actorId, now);
        return new Change(changed, reports.appendHistory(current, changed, actorId, request), false);
    }

    private AdminReportRepository.StoredReport chatReport(long actorId, long reportId) {
        var report = find(actorId, reportId, true);
        if (report.summary().targetType() != ReportType.CHAT_ROOM) throw new BusinessException(REPORT_EVIDENCE_NOT_FOUND);
        return report;
    }

    private AdminReportRepository.StoredReport find(long actorId, long reportId, boolean lockForAudit) {
        validateId(reportId);
        var report = reports.find(reportId, lockForAudit).orElseThrow(() -> new BusinessException(REPORT_NOT_FOUND));
        checkScope(report.summary(), actorId, Instant.now());
        return report;
    }

    private static void validateId(long id) {
        if (id <= 0) throw new BusinessException(INVALID_INPUT_VALUE);
    }

    private static void checkScope(Summary report, long actorId, Instant now) {
        if (!report.expiresAt().isAfter(now)) throw new BusinessException(REPORT_NOT_FOUND);
        if (report.reporterId() == actorId || report.targetUserId() == actorId) {
            throw new BusinessException(REPORT_REVIEW_FORBIDDEN);
        }
    }

    private static ReportReviewRequest normalize(ReportReviewRequest input) {
        if (input == null || input.status() == null || input.expectedVersion() < 0 || input.requestId() == null) {
            throw new BusinessException(INVALID_INPUT_VALUE);
        }
        String reason = input.reason() == null ? "" : input.reason().strip();
        if (reason.indexOf(0) >= 0 || reason.codePointCount(0, reason.length()) > 1000
            || (input.status().terminal() && reason.isEmpty())) throw new BusinessException(INVALID_INPUT_VALUE);
        return new ReportReviewRequest(input.status(), reason.isEmpty() ? null : reason, input.expectedVersion(), input.requestId());
    }
}
