// 구매 확정된 주문의 상태와 최초 확정 시각을 반환한다.
package com.kitschcatch.backend.domain.order.dto;
import com.kitschcatch.backend.domain.order.entity.OrderStatus;
import java.time.LocalDateTime;
public record PurchaseConfirmationResponse(String orderId, OrderStatus status, LocalDateTime confirmedAt) {}
