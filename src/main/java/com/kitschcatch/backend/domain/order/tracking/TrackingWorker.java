// 배송 웹훅 구독과 상태 조회를 DB 잠금 밖에서 실행한다.
package com.kitschcatch.backend.domain.order.tracking;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.shipment.tracking.enabled", havingValue = "true")
public class TrackingWorker {
    private final ShipmentTracking tracking;
    private final TrackingProvider provider;

    @Scheduled(fixedDelayString = "${app.shipment.tracking.delay-ms:3000}", initialDelayString = "${app.shipment.tracking.delay-ms:3000}")
    public void run() {
        var claim = tracking.claim();
        if (claim == null) return;
        var now = OffsetDateTime.now(ZoneOffset.UTC);
        var expiration = claim.expiresAt();
        TrackingProvider.Snapshot result = null;
        boolean failed = false;
        try {
            if (expiration == null || !expiration.isAfter(now.plusHours(24))) {
                var renewed = now.plusHours(48);
                provider.subscribe(claim.carrier(), claim.number(), claim.callback(), renewed);
                expiration = renewed;
            }
            if (claim.query()) result = provider.lookup(claim.carrier(), claim.number());
        } catch (RuntimeException e) {
            failed = true;
        }
        tracking.finish(claim, expiration, result, failed);
    }
}
