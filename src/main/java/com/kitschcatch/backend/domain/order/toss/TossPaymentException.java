// PG 오류의 안전한 코드와 해당 승인 요청의 확정 거절 여부를 전달한다.
package com.kitschcatch.backend.domain.order.toss;

import com.kitschcatch.backend.global.exception.BusinessException;
import com.kitschcatch.backend.global.exception.ErrorCode;

public class TossPaymentException extends BusinessException {
    private final String pgCode;
    private final boolean confirmedRejection;

    public TossPaymentException(String pgCode, boolean confirmedRejection) {
        super(ErrorCode.TOSS_PAYMENTS_REQUEST_FAILED);
        this.pgCode = pgCode;
        this.confirmedRejection = confirmedRejection;
    }

    public String pgCode() {
        return pgCode;
    }

    public boolean confirmedRejection() {
        return confirmedRejection;
    }
}
