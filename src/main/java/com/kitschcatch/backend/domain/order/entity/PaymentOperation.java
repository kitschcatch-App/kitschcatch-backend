// 결제 상태 변경 시 수행 중인 외부 작업의 종류를 표현하는 enum
package com.kitschcatch.backend.domain.order.entity;

public enum PaymentOperation {
	NONE,
	CONFIRM,
	CANCEL
}
