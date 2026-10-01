// 주문별 택배사와 송장 및 최초 발송 기록을 보존한다.
package com.kitschcatch.backend.domain.order.entity;
import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;
@Embeddable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Shipment {
    @Column(name="shipment_carrier_code", length=30) private String carrierCode;
    @Column(name="shipment_tracking_number", length=40) private String trackingNumber;
    @Column(name="shipment_registered_at") private LocalDateTime registeredAt;
    @Column(name="shipment_updated_at") private LocalDateTime updatedAt;
    public Shipment(String carrierCode, String trackingNumber) {
        this.carrierCode=carrierCode; this.trackingNumber=trackingNumber;
        this.registeredAt=LocalDateTime.now(); this.updatedAt=registeredAt;
    }
    public void update(String carrierCode, String trackingNumber) {
        if (matches(carrierCode,trackingNumber)) return;
        this.carrierCode=carrierCode; this.trackingNumber=trackingNumber; this.updatedAt=LocalDateTime.now();
    }
    public boolean matches(String carrierCode, String trackingNumber) {
        return this.carrierCode.equals(carrierCode) && this.trackingNumber.equals(trackingNumber);
    }
}
