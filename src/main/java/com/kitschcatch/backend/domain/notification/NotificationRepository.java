// 알림함을 사용자 범위로 조회하고 읽음 시각을 멱등하게 갱신한다.
package com.kitschcatch.backend.domain.notification;

import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.*;

public interface NotificationRepository extends JpaRepository<Notification, Long> {
  boolean existsByEventKeyAndUserId(String eventKey, long userId);

  Page<Notification> findByUserId(long userId, Pageable page);

  long countByUserIdAndReadAtIsNull(long userId);

  Optional<Notification> findByIdAndUserId(long id, long userId);

  @Modifying
  @Query(
      "update Notification n set n.readAt=:now where n.userId=:userId and n.id=:id and n.readAt is"
          + " null")
  int read(long userId, long id, LocalDateTime now);

  @Modifying
  @Query("update Notification n set n.readAt=:now where n.userId=:userId and n.readAt is null")
  int readAll(long userId, LocalDateTime now);
}
