// 여러 워커가 발송 작업을 중복 선택하지 않도록 잠근다.
package com.kitschcatch.backend.domain.notification;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.*;

public interface PushDeliveryRepository extends JpaRepository<PushDelivery, Long> {
  @Query(
      value =
          "SELECT * FROM push_deliveries WHERE status='PENDING' AND"
              + " next_attempt_at<=CURRENT_TIMESTAMP ORDER BY next_attempt_at,id LIMIT 1 FOR UPDATE"
              + " SKIP LOCKED",
      nativeQuery = true)
  Optional<PushDelivery> lockNext();

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("""
      select d from PushDelivery d
      where d.status = com.kitschcatch.backend.domain.notification.PushDelivery.Status.PENDING
        and d.notificationId in (
          select n.id from Notification n, ChatRoom r
          where n.type = :type and n.targetId = cast(r.id as String)
            and n.userId in (:first, :second)
            and ((r.buyer.id = :first and r.seller.id = :second)
              or (r.buyer.id = :second and r.seller.id = :first))
        )
      order by d.id
      """)
  List<PushDelivery> lockPendingChatBetween(@Param("first") long first, @Param("second") long second,
                                           @Param("type") NotificationType type);

  @Query("""
      select case when count(b) > 0 then true else false end
      from ChatRoom r, UserBlock b where r.id = :room
      and ((b.blocker.id = r.buyer.id and b.blocked.id = r.seller.id)
        or (b.blocker.id = r.seller.id and b.blocked.id = r.buyer.id))
      """)
  boolean isBlockedChat(@Param("room") long room);
}
