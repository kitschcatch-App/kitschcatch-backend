// 실제 지급과 준비 상태를 구별해 판매 대금 정산 정보를 반환한다.
package com.kitschcatch.backend.domain.order.dto;
import com.kitschcatch.backend.domain.order.entity.*;
import java.time.LocalDateTime;
public record SettlementResponse(String settlementId,String orderId,Long sellerId,Long amount,Long fee,
    OrderSettlement.Status status,LocalDateTime requestedAt,LocalDateTime settledAt) {
    public static SettlementResponse from(PurchaseOrder o) {
        var s=o.getSettlement();return new SettlementResponse(s.getSettlementId(),o.getOrderNumber(),o.getSellerId(),
            s.getAmount(),s.getFee(),s.getStatus(),s.getRequestedAt(),s.getSettledAt());
    }
}
