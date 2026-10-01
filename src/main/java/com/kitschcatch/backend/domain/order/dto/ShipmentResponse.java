// 배송사 추적을 추정하지 않고 등록된 송장 정보를 반환한다.
package com.kitschcatch.backend.domain.order.dto;
import com.kitschcatch.backend.domain.order.entity.*;
import java.time.LocalDateTime;
public record ShipmentResponse(String orderId, String carrierCode, String carrierName, String trackingNumber,
    String status, LocalDateTime registeredAt, LocalDateTime updatedAt, LocalDateTime shippedAt) {
    public static ShipmentResponse from(PurchaseOrder o) {
        var s=o.getShipment();
        return new ShipmentResponse(o.getOrderNumber(),s.getCarrierCode(),
            "CJ_LOGISTICS".equals(s.getCarrierCode()) ? "CJ대한통운" : "한진택배",s.getTrackingNumber(),
            "SHIPPED",s.getRegisteredAt(),s.getUpdatedAt(),s.getRegisteredAt());
    }
}
