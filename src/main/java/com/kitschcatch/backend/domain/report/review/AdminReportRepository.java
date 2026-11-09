// 운영자 신고 조회와 상태 전이, 감사 기록을 PostgreSQL에서 처리한다.
package com.kitschcatch.backend.domain.report.review;

import static com.kitschcatch.backend.domain.report.review.AdminReportResponses.*;
import com.kitschcatch.backend.domain.report.ReportType;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
class AdminReportRepository {
    private static final String SUMMARY_COLUMNS = """
        id,target_type,target_id,reporter_id,target_user_id,reason_code,status,created_at,expires_at,updated_at,version
        """;
    private static final String HISTORY_COLUMNS = "id,report_id,from_status,to_status,actor_id,reason,created_at,version,request_id";
    private static final RowMapper<Summary> SUMMARY = (row, index) -> summary(row);
    private static final RowMapper<History> HISTORY = (row, index) -> new History(
        row.getLong("id"), row.getLong("report_id"), ReportReviewStatus.valueOf(row.getString("from_status")),
        ReportReviewStatus.valueOf(row.getString("to_status")), row.getLong("actor_id"), row.getString("reason"),
        instant(row, "created_at"), row.getLong("version"), row.getObject("request_id", UUID.class));

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;

    record Filters(List<ReportReviewStatus> statuses, ReportType targetType, String reasonCode,
        Long reporterId, Long targetUserId, Instant from, Instant to) {}
    record StoredReport(Summary summary, String description, String snapshot, String messageIds,
        Long reviewStartedBy, Instant reviewStartedAt, Long closedBy, Instant closedAt) {}

    Page list(long actorId, Filters filter, int page, int size) {
        var params = new MapSqlParameterSource()
            .addValue("actor", actorId).addValue("now", Timestamp.from(Instant.now()))
            .addValue("statuses", filter.statuses().stream().map(Enum::name).toList());
        var where = new StringBuilder("""
             from reports where expires_at > :now and reporter_id <> :actor and target_user_id <> :actor
             and status in (:statuses)
            """);
        if (filter.targetType() != null) addFilter(where, params, "target_type = :target", "target", filter.targetType().name());
        if (filter.reasonCode() != null) addFilter(where, params, "reason_code = :reason", "reason", filter.reasonCode());
        if (filter.reporterId() != null) addFilter(where, params, "reporter_id = :reporter", "reporter", filter.reporterId());
        if (filter.targetUserId() != null) addFilter(where, params, "target_user_id = :targetUser", "targetUser", filter.targetUserId());
        if (filter.from() != null) addFilter(where, params, "created_at >= :from", "from", Timestamp.from(filter.from()));
        if (filter.to() != null) addFilter(where, params, "created_at < :to", "to", Timestamp.from(filter.to()));

        long total = named.queryForObject("select count(*)" + where, params, Long.class);
        params.addValue("size", size).addValue("offset", (long) page * size);
        var content = named.query("select " + SUMMARY_COLUMNS + where + " order by created_at,id limit :size offset :offset",
            params, SUMMARY);
        return new Page(content, page, size, total, (total + size - 1) / size);
    }

    private static void addFilter(StringBuilder where, MapSqlParameterSource params, String condition, String key, Object value) {
        where.append(" and ").append(condition);
        params.addValue(key, value);
    }

    Optional<StoredReport> find(long reportId, boolean lockForAudit) {
        String sql = "select " + SUMMARY_COLUMNS + """
            ,description,target_snapshot,message_ids,review_started_by,review_started_at,closed_by,closed_at
            from reports where id = ?
            """;
        return jdbc.query(sql + (lockForAudit ? " for share" : ""), (row, index) -> new StoredReport(summary(row),
            row.getString("description"), row.getString("target_snapshot"), row.getString("message_ids"),
            row.getObject("review_started_by", Long.class), instant(row, "review_started_at"),
            row.getObject("closed_by", Long.class), instant(row, "closed_at")), reportId).stream().findFirst();
    }

    Optional<Summary> lockForChange(long reportId) {
        return jdbc.query("select " + SUMMARY_COLUMNS + " from reports where id = ? for update", SUMMARY, reportId)
            .stream().findFirst();
    }

    List<History> history(long reportId) {
        return jdbc.query("select " + HISTORY_COLUMNS + " from report_review_history where report_id = ? order by version",
            HISTORY, reportId);
    }

    Optional<History> previous(long actorId, UUID requestId) {
        return jdbc.query("select " + HISTORY_COLUMNS + " from report_review_history where actor_id = ? and request_id = ?",
            HISTORY, actorId, requestId).stream().findFirst();
    }

    Summary update(Summary current, ReportReviewStatus next, long actorId, Instant now) {
        Timestamp at = Timestamp.from(now);
        // 새 상태에 따라 검토 시작 정보나 최종 처리 정보를 한 번 채운다.
        return jdbc.queryForObject("""
            update reports set status = ?, version = version + 1, updated_at = ?,
                review_started_by = coalesce(review_started_by, ?),
                review_started_at = coalesce(review_started_at, ?),
                closed_by = ?, closed_at = ?
            where id = ? returning
            """ + SUMMARY_COLUMNS, SUMMARY, next.name(), at, actorId, at,
            next.terminal() ? actorId : null, next.terminal() ? at : null, current.reportId());
    }

    long appendHistory(Summary before, Summary after, long actorId, ReportReviewRequest request) {
        return jdbc.queryForObject("""
            insert into report_review_history(report_id,from_status,to_status,actor_id,reason,created_at,version,request_id)
            values (?,?,?,?,?,?,?,?) returning id
            """, Long.class, before.reportId(), before.status().name(), after.status().name(), actorId, request.reason(),
            Timestamp.from(after.updatedAt()), after.version(), request.requestId());
    }

    void recordAccess(long reportId, long actorId, AccessType type, Long messageId) {
        jdbc.update("insert into report_review_access_log(report_id,actor_id,access_type,message_id) values (?,?,?,?)",
            reportId, actorId, type.name(), messageId);
    }

    enum AccessType { DETAIL, MESSAGES, IMAGE }

    private static Summary summary(ResultSet row) throws SQLException {
        return new Summary(row.getLong("id"), ReportType.valueOf(row.getString("target_type")), row.getLong("target_id"),
            row.getLong("reporter_id"), row.getLong("target_user_id"), row.getString("reason_code"),
            ReportReviewStatus.valueOf(row.getString("status")), instant(row, "created_at"), instant(row, "expires_at"),
            instant(row, "updated_at"), row.getLong("version"));
    }

    private static Instant instant(ResultSet row, String field) throws SQLException {
        Timestamp value = row.getTimestamp(field);
        return value == null ? null : value.toInstant();
    }
}
