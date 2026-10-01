// 한 SQL 스냅샷에서 거래 횟수와 정수 평점 합계를 조회한다.
package com.kitschcatch.backend.domain.review.repository;

import com.kitschcatch.backend.domain.review.dto.TrustInfoResponse;
import java.math.RoundingMode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import lombok.RequiredArgsConstructor;

@Repository
@RequiredArgsConstructor
public class TrustInfoRepository {
    private final JdbcTemplate jdbc;

    public TrustInfoResponse find(long userId) {
        return jdbc.queryForObject("""
            SELECT
                (SELECT count(*) FROM orders WHERE order_status = 'PURCHASE_CONFIRMED'
                    AND (user_id = ? OR seller_id = ?)) AS transactions,
                count(*) AS reviews, sum(CAST(rating AS DECIMAL(20, 0))) AS rating_sum
            FROM transaction_reviews WHERE recipient_id = ?
            """, (row, number) -> {
                long count = row.getLong("reviews");
                var average = count == 0 ? null : row.getBigDecimal("rating_sum")
                    .divide(java.math.BigDecimal.valueOf(count), 2, RoundingMode.HALF_UP);
                return new TrustInfoResponse(userId, row.getLong("transactions"), count, average);
            }, userId, userId, userId);
    }
}
