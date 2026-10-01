// 검증된 수취인 조회와 같은 식별자를 사용하는 지급·복구 계약이다.
package com.kitschcatch.backend.domain.order.settlement;
import java.time.LocalDateTime;
public interface SettlementGateway {
    boolean configured();
    Recipient recipient(String providerSellerId);
    Result request(Command command);
    Result lookup(Command command);
    record Command(String settlementId,long sellerId,long amount,long fee,String currency,String destination,String providerReference) {}
    record Result(String settlementId,Long amount,String currency,Status status,String providerReference,LocalDateTime settledAt,String destination) {}
    record Recipient(String id,String refSellerId,String status) {}
    enum Status { PENDING, COMPLETED, FAILED }
    static String sellerReference(long userId) {
        String id=Long.toString(userId,36).toUpperCase(java.util.Locale.ROOT);
        return "KC"+"0".repeat(Math.max(0,5-id.length()))+id;
    }
}
