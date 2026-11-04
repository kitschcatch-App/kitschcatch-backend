// 검색어 API의 저장 및 설정 계약을 정의한다.
package com.kitschcatch.backend.domain.search;

import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Scheduled;

@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name="app.search.cleanup-enabled",havingValue="true",matchIfMissing=true)
@Component
public class SearchCleanupScheduler {
    private final SearchService service;
    public SearchCleanupScheduler(SearchService service) { this.service=service; }
    @Scheduled(fixedDelayString="${app.search.cleanup-delay-ms:60000}")
    public void cleanup() { service.cleanupBatch(); }
}
