// 활성화된 환경에서만 커밋된 푸시 작업을 제한된 배치로 실행한다.
package com.kitschcatch.backend.domain.notification;

import org.springframework.scheduling.annotation.*;
import org.springframework.stereotype.Component;

@Component
@EnableScheduling
public class PushScheduler {
  private final PushWorker worker;

  public PushScheduler(PushWorker worker) {
    this.worker = worker;
  }

  @Scheduled(fixedDelayString = "${app.notifications.push.scan-delay:30s}")
  public void scan() {
    for (int i = 0; i < 20 && worker.processNext(); i++) {}
  }
}
