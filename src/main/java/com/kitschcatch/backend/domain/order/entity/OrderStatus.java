// 결제 및 구매 확정까지의 주문 상태를 구분한다.
package com.kitschcatch.backend.domain.order.entity;

public enum OrderStatus {
	PENDING,
	PAID,
	CANCELED,
	REFUNDED,
    PURCHASE_CONFIRMED
}
