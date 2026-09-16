// 결제 복구 작업의 진행 상태를 표현하는 enum
package com.kitschcatch.backend.domain.order.entity;

public enum PaymentRecoveryState {
	NONE,
	PENDING,
	REVIEW_REQUIRED
}
