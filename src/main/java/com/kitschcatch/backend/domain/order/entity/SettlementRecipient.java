// 판매자와 검증된 토스 셀러 식별자를 연결하며 계좌 원문을 저장하지 않는다.
package com.kitschcatch.backend.domain.order.entity;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.*;
@Entity @Table(name="settlement_recipients") @Getter @NoArgsConstructor(access=AccessLevel.PROTECTED)
public class SettlementRecipient {
    @Id private Long sellerId;
    @Column(nullable=false,unique=true,length=35) private String providerSellerId;
    @Column(nullable=false) private Long registeredBy;
    @Column(nullable=false) private LocalDateTime verifiedAt;
    public SettlementRecipient(long sellerId,String providerSellerId,long operatorId) {
        this.sellerId=sellerId;this.providerSellerId=providerSellerId;this.registeredBy=operatorId;this.verifiedAt=LocalDateTime.now();
    }
}
