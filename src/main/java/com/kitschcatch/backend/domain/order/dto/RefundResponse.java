// 저장된 환불 요청과 실제 PG 취소 확인 상태를 반환한다.
package com.kitschcatch.backend.domain.order.dto;
import com.kitschcatch.backend.domain.order.entity.*;
import java.time.LocalDateTime;
public record RefundResponse(String refundId,String orderId,Long amount,OrderRefund.Status status,String reason,
    LocalDateTime requestedAt,LocalDateTime completedAt) {
    public static RefundResponse from(PurchaseOrder o) {
        var r=o.getRefund(); return new RefundResponse(r.getRefundId(),o.getOrderNumber(),r.getAmount(),r.getStatus(),
            r.getReason(),r.getRequestedAt(),r.getCompletedAt());
    }
}
