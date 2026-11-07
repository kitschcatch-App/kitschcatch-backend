// 활성화한 환경에서 만료 신고를 작은 배치로 삭제한다.
package com.kitschcatch.backend.domain.report;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.transaction.annotation.Transactional;
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name="app.reports.cleanup-enabled",havingValue="true")
public class ReportCleanup {
    private final ReportRepository reports;
    @Transactional
    @Scheduled(fixedDelayString="${app.reports.cleanup-delay:1m}")
    public void run() { reports.cleanup(); }
}
