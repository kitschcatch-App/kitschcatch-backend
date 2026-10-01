// 주문 취소 확정 여부와 PG 결과 확인 중인 상태를 반환한다.
package com.kitschcatch.backend.domain.order.dto;
import com.kitschcatch.backend.domain.order.entity.OrderStatus;
import java.time.LocalDateTime;
public record CancelOrderResponse(String orderId, OrderStatus status, LocalDateTime canceledAt, boolean processing) {}
