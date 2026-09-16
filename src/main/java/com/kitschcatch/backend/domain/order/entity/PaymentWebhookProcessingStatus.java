// 결제 웹훅 수신 기록의 처리 상태를 표현하는 enum
package com.kitschcatch.backend.domain.order.entity;

public enum PaymentWebhookProcessingStatus {
	RECEIVED,
	PROCESSING,
	RETRY_WAIT,
	PROCESSED,
	IGNORED
}
