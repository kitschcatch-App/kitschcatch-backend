// 주문별 중복 확인과 상대방에게 남겨진 후기를 페이지로 조회한다.
package com.kitschcatch.backend.domain.review.repository;

import com.kitschcatch.backend.domain.review.entity.TransactionReview;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TransactionReviewRepository extends JpaRepository<TransactionReview, Long> {
    boolean existsByOrderIdAndAuthorId(long orderId, long authorId);
    List<TransactionReview> findByOrderIdOrderByCreatedAtAscIdAsc(long orderId);
    Page<TransactionReview> findByRecipientId(long recipientId, Pageable pageable);
}
