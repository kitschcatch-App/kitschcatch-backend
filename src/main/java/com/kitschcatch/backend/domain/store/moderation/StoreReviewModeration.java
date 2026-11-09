// 신고 근거와 별도 권한을 확인하고 리뷰 노출 조치를 기록한다.
package com.kitschcatch.backend.domain.store.moderation;

import com.kitschcatch.backend.domain.report.review.ReportReviewAccess;
import com.kitschcatch.backend.domain.store.dto.StoreReviewResponse;
import com.kitschcatch.backend.domain.store.service.StoreReviewImageService;
import com.kitschcatch.backend.global.exception.BusinessException;
import com.kitschcatch.backend.global.exception.ErrorCode;
import jakarta.validation.constraints.*;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class StoreReviewModeration {
    public enum Action { HIDE, RESTORE }
    public record Command(@NotNull Action action, @Positive Long reportId,
                          @NotBlank @Size(max = 1000) String reason,
                          @NotNull @PositiveOrZero Long expectedVersion, @NotNull UUID requestId) {}
    public record Review(long id, long storeId, long authorId, int rating, long version, String moderationStatus,
                         boolean publiclyVisible, boolean deleted, boolean authorActive) {}
    public record ReportLink(long reportId, String status, OffsetDateTime expiresAt) {}
    public record Detail(Review review, String content, List<StoreReviewResponse.Image> images,
                         List<ReportLink> relatedReports) {}
    public record Entry(long id, long reviewId, long actorId, long reportId, long reporterId, Action action,
                        String reason, long version, OffsetDateTime createdAt) {}
    public record Result(Review review, Entry history, boolean replayed) {}

    private static final String REVIEWS = """
        select r.*, u.withdrawn_at is null as author_active
        from store_reviews r join users u on u.id=r.author_id
        """;
    private static final RowMapper<Review> REVIEW = (rs, row) -> {
        boolean hidden = rs.getObject("hidden_at") != null;
        boolean deleted = rs.getObject("deleted_at") != null;
        boolean active = rs.getBoolean("author_active");
        return new Review(rs.getLong("id"), rs.getLong("store_id"), rs.getLong("author_id"),
            rs.getInt("rating"), rs.getLong("version"), hidden ? "HIDDEN" : "CLEAR",
            !hidden && !deleted && active, deleted, active);
    };
    private static final RowMapper<Entry> ENTRY = (rs, row) -> new Entry(rs.getLong("id"), rs.getLong("review_id"),
        rs.getLong("actor_id"), rs.getLong("report_id"), rs.getLong("reporter_id"),
        Action.valueOf(rs.getString("action")), rs.getString("reason"), rs.getLong("version"),
        rs.getObject("created_at", OffsetDateTime.class));

    private final JdbcTemplate jdbc;
    private final ReportReviewAccess reportAccess;
    private final StoreReviewImageService images;

    public boolean allowed(long actor) {
        return reportAccess.allowed(actor) && Boolean.TRUE.equals(jdbc.queryForObject("""
            select exists(select 1 from store_review_moderators where user_id=? and revoked_at is null)
            """, Boolean.class, actor));
    }

    @Transactional(readOnly = true)
    public List<Review> list(long actor, Long store, Boolean hidden, int page, int size) {
        require(actor);
        checkPage(page, size);
        return jdbc.query(REVIEWS + """
            where r.author_id<>? and not exists (
                select 1 from reports p where p.target_type='STORE_REVIEW' and p.target_id=r.id
                  and p.reporter_id=? and p.expires_at>now()
            )
            and not exists (select 1 from store_review_moderation_history h where h.review_id=r.id and h.reporter_id=?)
            and (cast(? as bigint) is null or r.store_id=?)
            and (cast(? as boolean) is null or (r.hidden_at is not null)=?)
            order by r.id desc limit ? offset ?
            """, REVIEW, actor, actor, actor, store, store, hidden, hidden, size, (long) page * size);
    }

    @Transactional(readOnly = true)
    public Detail detail(long actor, long id) {
        require(actor);
        var review = review(id, false);
        requireIndependent(actor, review);
        String content = jdbc.queryForObject("""
            select case when deleted_at is null or evidence_until>now() then content end
            from store_reviews where id=?
            """, String.class, id);
        var relatedReports = jdbc.query("""
            select id,status,expires_at from reports where target_type='STORE_REVIEW' and target_id=?
              and target_user_id=? and expires_at>now()
            order by created_at desc,id desc limit 20
            """, (rs, row) -> new ReportLink(rs.getLong(1), rs.getString(2),
            rs.getObject(3, OffsetDateTime.class)), id, review.authorId());
        return new Detail(review, content, content == null ? List.of()
            : images.responses(List.of(id)).getOrDefault(id, List.of()), relatedReports);
    }

    @Transactional(readOnly = true)
    public List<Entry> history(long actor, long id, int page, int size) {
        require(actor);
        requireIndependent(actor, review(id, false));
        checkPage(page, size);
        return jdbc.query("""
            select * from store_review_moderation_history where review_id=?
            order by version desc limit ? offset ?
            """, ENTRY, id, size, (long) page * size);
    }

    @Transactional
    public Result change(long actor, long id, Command command) {
        require(actor);
        long author = review(id, false).authorId();
        // 작성자 수정·삭제 및 권한 회수와 동일한 사용자 우선 잠금 순서를 사용한다.
        jdbc.queryForList("select id from users where id in (?,?) order by id for update", Long.class, actor, author);
        reportAccess.lock(actor);
        if (jdbc.queryForList("select user_id from store_review_moderators where user_id=? and revoked_at is null for share",
            Long.class, actor).isEmpty()) throw forbidden();

        var review = review(id, true);
        requireIndependent(actor, review);
        var existing = jdbc.query("select * from store_review_moderation_history where actor_id=? and request_id=?",
            ENTRY, actor, command.requestId());
        if (!existing.isEmpty()) {
            var entry = existing.getFirst();
            if (entry.reviewId() != id || entry.action() != command.action()
                || !entry.reason().equals(command.reason().strip()) || entry.version() - 1 != command.expectedVersion()
                || command.action() == Action.HIDE && !Long.valueOf(entry.reportId()).equals(command.reportId())
                || command.action() == Action.RESTORE && command.reportId() != null) throw conflict();
            return new Result(review, entry, true);
        }
        if (review.version() != command.expectedVersion()) throw conflict();
        boolean hide = command.action() == Action.HIDE;
        long reportId;
        long reporter;
        if (hide) {
            if (review.deleted() || !review.authorActive() || !review.moderationStatus().equals("CLEAR")) throw conflict();
            if (command.reportId() == null) throw new BusinessException(ErrorCode.BAD_REQUEST);
            reportId = command.reportId();
            reporter = jdbc.queryForList("""
                select reporter_id from reports where id=? and target_id=? and target_user_id=?
                  and target_type='STORE_REVIEW' and status in ('IN_REVIEW','RESOLVED') and expires_at>now()
                for share
                """, Long.class, reportId, id, author).stream().findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.REPORT_NOT_FOUND));
        } else {
            if (command.reportId() != null) throw new BusinessException(ErrorCode.BAD_REQUEST);
            if (!review.moderationStatus().equals("HIDDEN")) throw conflict();
            var prior = jdbc.query("""
                select * from store_review_moderation_history where review_id=? and action='HIDE'
                order by version desc limit 1
                """, ENTRY, id).stream().findFirst().orElseThrow(StoreReviewModeration::conflict);
            reportId = prior.reportId();
            reporter = prior.reporterId();
        }
        if (reporter == actor) throw forbidden();

        jdbc.update("""
            update store_reviews set hidden_at=case when ? then now() end, updated_at=now(), version=version+1
            where id=?
            """, hide, id);
        var entry = jdbc.queryForObject("""
            insert into store_review_moderation_history
                (review_id, actor_id, report_id, reporter_id, action, reason, request_id, version)
            values (?,?,?,?,?,?,?,?) returning *
            """, ENTRY, id, actor, reportId, reporter, command.action().name(), command.reason().strip(),
            command.requestId(), review.version() + 1);
        return new Result(review(id, false), entry, false);
    }

    private Review review(long id, boolean lock) {
        return jdbc.query(REVIEWS + " where r.id=?" + (lock ? " for update of r" : ""), REVIEW, id)
            .stream().findFirst().orElseThrow(() -> new BusinessException(ErrorCode.STORE_REVIEW_NOT_FOUND));
    }

    private void requireIndependent(long actor, Review review) {
        if (review.authorId() == actor || Boolean.TRUE.equals(jdbc.queryForObject("""
            select exists(select 1 from reports where target_type='STORE_REVIEW' and target_id=?
              and reporter_id=? and expires_at>now())
            or exists(select 1 from store_review_moderation_history where review_id=? and reporter_id=?)
            """, Boolean.class, review.id(), actor, review.id(), actor))) throw forbidden();
    }

    private void require(long actor) {
        if (!allowed(actor)) throw forbidden();
    }

    private static void checkPage(int page, int size) {
        if (page < 0 || size < 1 || size > 100) throw new BusinessException(ErrorCode.BAD_REQUEST);
    }

    private static BusinessException forbidden() {
        return new BusinessException(ErrorCode.REPORT_REVIEW_FORBIDDEN);
    }

    private static BusinessException conflict() {
        return new BusinessException(ErrorCode.STORE_REVIEW_VERSION_CONFLICT);
    }
}
