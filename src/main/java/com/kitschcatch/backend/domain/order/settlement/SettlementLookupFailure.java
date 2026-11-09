// 외부 조회 오류에서 민감한 응답 본문을 버리고 재시도 정보만 전달한다.
package com.kitschcatch.backend.domain.order.settlement;

import com.kitschcatch.backend.global.exception.BusinessException;
import com.kitschcatch.backend.global.exception.ErrorCode;

final class SettlementLookupFailure extends BusinessException {
    private final String category;
    private final long retryAfterSeconds;

    SettlementLookupFailure(String category, long retryAfterSeconds) {
        super(ErrorCode.SETTLEMENT_PROVIDER_FAILED);
        this.category = category;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    String category() { return category; }
    long retryAfterSeconds() { return retryAfterSeconds; }
}
