// 저장된 환불 요청과 실제 PG 취소 확인 상태를 반환한다.
package com.kitschcatch.backend.domain.order.dto;
import com.kitschcatch.backend.domain.order.entity.*;
import java.time.LocalDateTime;
import java.util.List;
public record RefundResponse(String refundId,String orderId,Long amount,OrderRefund.Status status,String reason,
    LocalDateTime requestedAt,LocalDateTime completedAt,String reviewReason,LocalDateTime reviewedAt,Boolean returnReceived,LocalDateTime withdrawnAt,
    List<RefundImageDtos.Image> images) {
    public static RefundResponse from(PurchaseOrder o, List<RefundImageDtos.Image> images) {
        var r=o.getRefund(); return new RefundResponse(r.getRefundId(),o.getOrderNumber(),r.getAmount(),r.getStatus(),
            r.getReason(),r.getRequestedAt(),r.getCompletedAt(),r.getReviewReason(),r.getReviewedAt(),r.getReturnReceived(),r.getWithdrawnAt(),images);
    }
}
