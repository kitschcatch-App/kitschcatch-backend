// 판매 대금 지급 요청 식별자와 확정된 금액·외부 지급 결과를 보존한다.
package com.kitschcatch.backend.domain.order.entity;
import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;
import java.util.UUID;
@Embeddable @Getter @NoArgsConstructor(access=AccessLevel.PROTECTED)
public class OrderSettlement {
    @Column(name="settlement_id",length=50) private String settlementId;
    @Column(name="settlement_amount") private Long amount;
    @Column(name="settlement_fee") private Long fee;
    @Enumerated(EnumType.STRING) @Column(name="settlement_status",length=30) private Status status;
    @Column(name="settlement_requested_at") private LocalDateTime requestedAt;
    @Column(name="settlement_settled_at") private LocalDateTime settledAt;
    @Column(name="settlement_provider_reference",length=200) private String providerReference;
    public enum Status { WAITING, PROCESSING, UNKNOWN, FAILED, COMPLETED }
    public static OrderSettlement waiting() {
        var s=new OrderSettlement(); s.settlementId="SET-"+UUID.randomUUID().toString().replace("-","").toUpperCase();
        s.status=Status.WAITING; return s;
    }
    public void start(long amount,long fee) {
        this.amount=amount; this.fee=fee; this.status=Status.PROCESSING; this.requestedAt=LocalDateTime.now();
    }
    public void pending() { if(status!=Status.COMPLETED) status=Status.PROCESSING; }
    public void unknown() { if(status!=Status.COMPLETED) status=Status.UNKNOWN; }
    public void failed() { if(status!=Status.COMPLETED) status=Status.FAILED; }
    public void complete(String reference,LocalDateTime time) {
        if(status==Status.COMPLETED) return;
        status=Status.COMPLETED;providerReference=reference;settledAt=time;
    }
}
