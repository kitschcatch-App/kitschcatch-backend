// 결제 시도가 수행하는 외부 PG 작업의 종류를 표현하는 enum
package com.kitschcatch.backend.domain.order.entity;

public enum PaymentAttemptOperation {
	CONFIRM,
	CANCEL
}
