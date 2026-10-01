// 푸시 시도 이력을 영속화한다.
package com.kitschcatch.backend.domain.notification;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PushAttemptRepository extends JpaRepository<PushAttempt, Long> {}
