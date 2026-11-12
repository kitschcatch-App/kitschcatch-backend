// 반품 사진의 주문 범위 조회와 연결·정리에 필요한 행 잠금을 제공한다.
package com.kitschcatch.backend.domain.order.repository;

import com.kitschcatch.backend.domain.order.entity.RefundImage;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;

public interface RefundImageRepository extends JpaRepository<RefundImage, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from RefundImage i where i.id = :id and i.orderId = :orderId and i.uploaderId = :uploaderId")
    Optional<RefundImage> lockOwned(long id, long orderId, long uploaderId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from RefundImage i where i.id = :id")
    Optional<RefundImage> lockById(long id);

    long countByUploaderIdAndCreatedAtGreaterThanEqual(long uploaderId, Instant cutoff);

    List<RefundImage> findByOrderIdAndRefundIdOrderBySortOrder(long orderId, String refundId);

    @Query("""
        select i.id from RefundImage i where i.cleanupAt <= :now
        and (i.state <> 'ATTACHED' or i.temporaryCleaned = false)
        order by i.cleanupAt, i.id
        """)
    List<Long> due(Instant now, Pageable page);
}
