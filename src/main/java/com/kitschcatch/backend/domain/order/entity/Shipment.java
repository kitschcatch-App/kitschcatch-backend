// 주문별 택배사와 송장 및 최초 발송 기록을 보존한다.
package com.kitschcatch.backend.domain.order.entity;
import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
@Embeddable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Shipment {
    @Column(name="shipment_carrier_code", length=30) private String carrierCode;
    @Column(name="shipment_tracking_number", length=40) private String trackingNumber;
    @Column(name="shipment_registered_at") private LocalDateTime registeredAt;
    @Column(name="shipment_updated_at") private LocalDateTime updatedAt;
    @Column(name="shipment_revision") private Long revision;
    public Shipment(String carrierCode, String trackingNumber) {
        this.carrierCode=carrierCode; this.trackingNumber=trackingNumber;
        this.registeredAt=LocalDateTime.now().truncatedTo(ChronoUnit.MICROS); this.updatedAt=registeredAt;
        this.revision=1L;
    }
    public boolean update(String carrierCode, String trackingNumber) {
        if (matches(carrierCode,trackingNumber)) return false;
        this.carrierCode=carrierCode; this.trackingNumber=trackingNumber; this.updatedAt=LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
        // 이전 버전에서 등록한 송장에도 최초 수정 번호를 부여한다.
        this.revision=revision==null?2L:Math.incrementExact(revision);
        return true;
    }
    public boolean matches(String carrierCode, String trackingNumber) {
        return this.carrierCode.equals(carrierCode) && this.trackingNumber.equals(trackingNumber);
    }
}
