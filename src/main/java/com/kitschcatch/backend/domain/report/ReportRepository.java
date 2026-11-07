// 신고 저장과 본인 조회 및 만료 정리를 PostgreSQL에서 수행한다.
package com.kitschcatch.backend.domain.report;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.*;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;
@Repository
@RequiredArgsConstructor
public class ReportRepository {
    private final JdbcTemplate jdbc;
    private static final JsonMapper JSON = JsonMapper.builder().build();
    public record Report(long reportId, ReportType targetType, long targetId, String reasonCode,
                         String status, Instant createdAt, String description, List<Long> messageIds) {
        public Summary summary() { return new Summary(reportId,targetType,targetId,reasonCode,status,createdAt); }
    }
    public record Summary(long reportId, ReportType targetType, long targetId, String reasonCode,
                          String status, Instant createdAt) {}
    public record Saved(Report report, String fingerprint) {}
    private static final String COLUMNS = "id,target_type,target_id,reason_code,status,created_at,description,message_ids,request_fingerprint";
    private static final RowMapper<Saved> ROW = (rs, n) -> new Saved(new Report(rs.getLong("id"),
        ReportType.valueOf(rs.getString("target_type")), rs.getLong("target_id"), rs.getString("reason_code"),
        rs.getString("status"), rs.getTimestamp("created_at").toInstant(), rs.getString("description"),
        JSON.readValue(rs.getString("message_ids"), new tools.jackson.core.type.TypeReference<List<Long>>() {})), rs.getString("request_fingerprint"));
    public Optional<Saved> existing(long actor, ReportRequest r, Instant now) {
        return jdbc.query("select " + COLUMNS + " from reports where reporter_id=? and target_type=? and target_id=? and expires_at>?", ROW,
            actor, r.targetType().name(), r.targetId(), Timestamp.from(now)).stream().findFirst();
    }
    public Optional<Report> detail(long actor, long id) {
        return jdbc.query("select " + COLUMNS + " from reports where reporter_id=? and id=? and expires_at>current_timestamp", ROW, actor, id)
            .stream().map(Saved::report).findFirst();
    }
    public List<Report> list(long actor, int page, int size) {
        return jdbc.query("select " + COLUMNS + " from reports where reporter_id=? and expires_at>current_timestamp order by created_at desc,id desc limit ? offset ?", ROW,
            actor, size, (long)page*size).stream().map(Saved::report).toList();
    }
    public long total(long actor) { return jdbc.queryForObject("select count(*) from reports where reporter_id=? and expires_at>current_timestamp", Long.class, actor); }
    public long countSince(long actor, Instant cutoff) { return jdbc.queryForObject("select count(*) from reports where reporter_id=? and created_at>=?", Long.class, actor, Timestamp.from(cutoff)); }
    public Report insert(long actor, long owner, ReportRequest r, String fingerprint, Map<String,Object> snapshot, Instant now, Instant expires) {
        long id = jdbc.queryForObject("""
            insert into reports(reporter_id,target_type,target_id,target_user_id,reason_code,description,
            request_fingerprint,message_ids,target_snapshot,created_at,expires_at)
            values(?,?,?,?,?,?,?,cast(? as jsonb),cast(? as jsonb),?,?) returning id
            """, Long.class, actor, r.targetType().name(), r.targetId(), owner, r.reasonCode(), r.description(), fingerprint,
            JSON.writeValueAsString(r.messageIds()), JSON.writeValueAsString(snapshot), Timestamp.from(now), Timestamp.from(expires));
        return new Report(id,r.targetType(),r.targetId(),r.reasonCode(),"RECEIVED",now,r.description(),r.messageIds());
    }
    public void removeExpiredTarget(long actor, ReportRequest r, Instant now) {
        jdbc.update("delete from reports where reporter_id=? and target_type=? and target_id=? and expires_at<=?", actor,r.targetType().name(),r.targetId(),Timestamp.from(now));
    }
    public int cleanup() { return jdbc.update("""
        delete from reports where id in (select id from reports where expires_at<=current_timestamp
        order by expires_at,id limit 100 for update skip locked)
        """); }
}
