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
    @Column(name="refund_reviewed_by") private Long reviewedBy;
    @Column(name="refund_reviewed_at") private LocalDateTime reviewedAt;
    @Column(name="refund_review_reason",length=200) private String reviewReason;
    @Column(name="refund_return_received") private Boolean returnReceived;
    @Column(name="refund_withdrawn_at") private LocalDateTime withdrawnAt;
    public enum Status { REQUESTED, PROCESSING, COMPLETED, REJECTED, WITHDRAWN }
    public boolean active() { return status!=Status.REJECTED && status!=Status.WITHDRAWN; }
    public void review(long operatorId,String reason,boolean approved) {
        reviewedBy=operatorId; reviewedAt=LocalDateTime.now(); reviewReason=reason;
        returnReceived=approved; status=approved?Status.PROCESSING:Status.REJECTED;
    }
    public void withdraw() { status=Status.WITHDRAWN; withdrawnAt=LocalDateTime.now(); }
    public boolean sameDecision(String reason,boolean approved) {
        return reviewedAt!=null && reviewReason.equals(reason) && Boolean.valueOf(approved).equals(returnReceived);
    }
    public OrderRefund(long amount,String reason) {
        refundId="REF-"+UUID.randomUUID().toString().replace("-","").toUpperCase();
        this.amount=amount; this.reason=reason; this.status=Status.REQUESTED; this.requestedAt=LocalDateTime.now();
    }
    public void start() { status=Status.PROCESSING; }
    public void complete() { status=Status.COMPLETED; if (completedAt==null) completedAt=LocalDateTime.now(); }
    public boolean matches(long amount,String reason) { return this.amount==amount && this.reason.equals(reason); }
}
