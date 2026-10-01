// 여러 워커가 발송 작업을 중복 선택하지 않도록 잠근다.
package com.kitschcatch.backend.domain.notification;

import java.util.Optional;
import org.springframework.data.jpa.repository.*;

public interface PushDeliveryRepository extends JpaRepository<PushDelivery, Long> {
  @Query(
      value =
          "SELECT * FROM push_deliveries WHERE status='PENDING' AND"
              + " next_attempt_at<=CURRENT_TIMESTAMP ORDER BY next_attempt_at,id LIMIT 1 FOR UPDATE"
              + " SKIP LOCKED",
      nativeQuery = true)
  Optional<PushDelivery> lockNext();
}
