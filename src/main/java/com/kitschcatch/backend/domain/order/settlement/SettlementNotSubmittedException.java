// 지급 POST를 보내기 전 실패임을 구분해 같은 지급의 안전한 재검사를 허용한다.
package com.kitschcatch.backend.domain.order.settlement;
import com.kitschcatch.backend.global.exception.ErrorCode;
public class SettlementNotSubmittedException extends RuntimeException {
    private final ErrorCode errorCode;
    public SettlementNotSubmittedException(ErrorCode errorCode) { super(errorCode.getMessage()); this.errorCode=errorCode; }
    public ErrorCode errorCode() { return errorCode; }
}
