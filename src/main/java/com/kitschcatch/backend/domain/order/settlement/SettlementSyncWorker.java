// 외부 지급 결과 조회만 실행하고 최초 지급 요청에는 접근하지 않는다.
package com.kitschcatch.backend.domain.order.settlement;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.settlement.sync-enabled", havingValue = "true")
public class SettlementSyncWorker {
    private final SettlementGateway gateway;
    private final SettlementSyncJobs jobs;

    @Scheduled(fixedDelayString = "${app.settlement.sync-delay-ms:15000}", initialDelayString = "${app.settlement.sync-delay-ms:15000}")
    public void synchronize() {
        if (!gateway.configured()) return;
        var claim = jobs.claim();
        if (claim == null) return;
        SettlementGateway.Result result = null;
        String error = null;
        if (claim.command() != null) {
            try {
                result = gateway.lookup(claim.command());
            } catch (RuntimeException e) {
                error = "PROVIDER_LOOKUP_FAILED";
            }
        }
        jobs.finish(claim, result, error);
    }
}
