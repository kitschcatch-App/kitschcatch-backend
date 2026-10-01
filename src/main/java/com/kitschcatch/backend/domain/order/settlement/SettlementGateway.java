// 실제 지급 제공자가 멱등 지급 요청과 식별자 조회로 구현해야 하는 연동 계약이다.
package com.kitschcatch.backend.domain.order.settlement;
import java.time.LocalDateTime;
public interface SettlementGateway {
    boolean configured();
    Result request(Command command);
    Result lookup(String settlementId);
    record Command(String settlementId,long sellerId,long amount,long fee,String currency) {}
    record Result(String settlementId,Long amount,String currency,Status status,String providerReference,LocalDateTime settledAt) {}
    enum Status { PENDING, COMPLETED, FAILED }
}
