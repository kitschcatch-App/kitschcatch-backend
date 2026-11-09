// DB에 부여된 신고 심사 권한과 운영자의 활성 상태를 검사한다.
package com.kitschcatch.backend.domain.report.review;

import com.kitschcatch.backend.global.exception.BusinessException;
import com.kitschcatch.backend.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class ReportReviewAccess {
    private final JdbcTemplate jdbc;

    public boolean allowed(long actorId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
            select exists (
                select 1 from users u join report_reviewers r on r.user_id = u.id
                where u.id = ? and u.withdrawn_at is null and r.revoked_at is null
            )
            """, Boolean.class, actorId));
    }

    public void require(long actorId) {
        if (!allowed(actorId)) throw new BusinessException(ErrorCode.REPORT_REVIEW_FORBIDDEN);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void lock(long actorId) {
        // 탈퇴 및 권한 관리 스크립트와 같은 순서로 사용자와 권한 행을 잠근다.
        if (jdbc.queryForList("select id from users where id = ? and withdrawn_at is null for update",
            Long.class, actorId).isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_AUTH_TOKEN);
        }
        if (jdbc.queryForList("select user_id from report_reviewers where user_id = ? and revoked_at is null for share",
            Long.class, actorId).isEmpty()) {
            throw new BusinessException(ErrorCode.REPORT_REVIEW_FORBIDDEN);
        }
    }
}
