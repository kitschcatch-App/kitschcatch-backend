// 토큰 소유권 변경과 발송을 동일한 행 잠금으로 직렬화한다.
package com.kitschcatch.backend.domain.notification;

import jakarta.persistence.LockModeType;
import java.util.*;
import org.springframework.data.jpa.repository.*;

public interface DeviceTokenRepository extends JpaRepository<DeviceToken, Long> {
  @Modifying
  @Query(
      """
      insert into DeviceToken (token, userId, platform, active, ownershipVersion)
      values (:token, :userId, :platform, true, 0L)
      on conflict (token) do update set token = excluded.token
      """)
  int ensureTokenRow(long userId, String token, String platform);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select t from DeviceToken t where t.token=:token")
  Optional<DeviceToken> lockByToken(String token);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select t from DeviceToken t where t.id=:id")
  Optional<DeviceToken> lockById(long id);

  List<DeviceToken> findByUserIdAndActiveTrueOrderById(long userId);
}
