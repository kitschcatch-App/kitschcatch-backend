// 본인 견적을 주문 생성 트랜잭션 안에서 잠가 중복 사용을 막는다.
package com.kitschcatch.backend.domain.order.repository;
import com.kitschcatch.backend.domain.order.entity.OrderQuote;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import java.util.Optional;
public interface OrderQuoteRepository extends JpaRepository<OrderQuote,String> {
 @Lock(LockModeType.PESSIMISTIC_WRITE)
 Optional<OrderQuote> findByIdAndUserId(String id,Long userId);
}
