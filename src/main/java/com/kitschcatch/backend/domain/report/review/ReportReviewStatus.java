// 신고 심사의 상태와 허용 전이를 정의한다.
package com.kitschcatch.backend.domain.report.review;

public enum ReportReviewStatus {
    RECEIVED, IN_REVIEW, RESOLVED, DISMISSED;

    public boolean terminal() {
        return this == RESOLVED || this == DISMISSED;
    }

    public boolean canMoveTo(ReportReviewStatus next) {
        return (this == RECEIVED && next == IN_REVIEW) || (this == IN_REVIEW && next.terminal());
    }
}
