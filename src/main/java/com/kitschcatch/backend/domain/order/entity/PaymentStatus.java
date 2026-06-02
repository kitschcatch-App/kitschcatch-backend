// 결제 처리 단계별 상태를 표현하는 enum
package com.kitschcatch.backend.domain.order.entity;

public enum PaymentStatus {
	READY,
	SUCCESS,
	CANCELED
}
