// 전액 환불 요청과 PG 확인 완료 시각을 주문에 보존한다.
package com.kitschcatch.backend.domain.order.entity;
import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;
import java.util.UUID;
@Embeddable
@Getter
@NoArgsConstructor(access=AccessLevel.PROTECTED)
public class OrderRefund {
    @Column(name="refund_id",length=50) private String refundId;
    @Column(name="refund_amount") private Long amount;
    @Column(name="refund_reason",length=200) private String reason;
    @Enumerated(EnumType.STRING) @Column(name="refund_status",length=30) private Status status;
    @Column(name="refund_requested_at") private LocalDateTime requestedAt;
    @Column(name="refund_completed_at") private LocalDateTime completedAt;
    public enum Status { REQUESTED, PROCESSING, COMPLETED }
    public OrderRefund(long amount,String reason) {
        refundId="REF-"+UUID.randomUUID().toString().replace("-","").toUpperCase();
        this.amount=amount; this.reason=reason; this.status=Status.REQUESTED; this.requestedAt=LocalDateTime.now();
    }
    public void start() { status=Status.PROCESSING; }
    public void complete() { status=Status.COMPLETED; if (completedAt==null) completedAt=LocalDateTime.now(); }
    public boolean matches(long amount,String reason) { return this.amount==amount && this.reason.equals(reason); }
}
