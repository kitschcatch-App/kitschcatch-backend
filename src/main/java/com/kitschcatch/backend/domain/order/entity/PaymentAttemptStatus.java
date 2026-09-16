// 결제 시도의 처리 및 복구 상태를 표현하는 enum
package com.kitschcatch.backend.domain.order.entity;

public enum PaymentAttemptStatus {
	PREPARED,
	PROCESSING,
	UNKNOWN,
	SUCCEEDED,
	FAILED,
	EXPIRED
}
