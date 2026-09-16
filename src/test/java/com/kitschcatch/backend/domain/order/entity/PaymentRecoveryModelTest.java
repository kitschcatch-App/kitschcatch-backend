// 결제 복구 도메인 모델의 상태 전이를 검증하는 테스트
package com.kitschcatch.backend.domain.order.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PaymentRecoveryModelTest {

	@Test
	@DisplayName("결제 승인과 취소의 처리 작업 종류를 구분한다")
	void distinguishesConfirmAndCancelOperations() {
		PurchaseOrder order = mock(PurchaseOrder.class);
		Payment payment = payment(order, PaymentStatus.READY);

		payment.startConfirmation("payment-key");

		assertThat(payment.getPaymentStatus()).isEqualTo(PaymentStatus.PROCESSING);
		assertThat(payment.getProcessingOperation()).isEqualTo(PaymentOperation.CONFIRM);
		assertThat(payment.getRecoveryState()).isEqualTo(PaymentRecoveryState.PENDING);

		payment.startProcessing(PaymentOperation.CANCEL);

		assertThat(payment.getProcessingOperation()).isEqualTo(PaymentOperation.CANCEL);
		assertThat(payment.getRecoveryState()).isEqualTo(PaymentRecoveryState.PENDING);
	}

	@Test
	@DisplayName("확정 실패와 복구 확인 필요 상태를 별도로 보존한다")
	void keepsFailureAndReviewStateSeparate() {
		PurchaseOrder order = mock(PurchaseOrder.class);
		Payment payment = payment(order, PaymentStatus.PROCESSING);

		payment.markFailed("REJECT_CARD_PAYMENT");

		assertThat(payment.getPaymentStatus()).isEqualTo(PaymentStatus.FAILED);
		assertThat(payment.getRecoveryState()).isEqualTo(PaymentRecoveryState.NONE);
		assertThat(payment.getLastFailureCode()).isEqualTo("REJECT_CARD_PAYMENT");

		payment.markRecoveryPending();
		payment.markReviewRequired("IDENTIFIER_MISMATCH");

		assertThat(payment.getRecoveryState()).isEqualTo(PaymentRecoveryState.REVIEW_REQUIRED);
		assertThat(payment.getLastFailureCode()).isEqualTo("IDENTIFIER_MISMATCH");
	}

	@Test
	@DisplayName("결제 시도는 복구 작업에 필요한 상태와 점유 정보를 관리한다")
	void managesAttemptRecoveryState() {
		Payment payment = payment(mock(PurchaseOrder.class), PaymentStatus.READY);
		LocalDateTime now = LocalDateTime.now();
		PaymentAttempt attempt = PaymentAttempt.builder()
			.attemptId("ATT-1")
			.payment(payment)
			.sequenceNumber(1)
			.operation(PaymentAttemptOperation.CONFIRM)
			.attemptStatus(PaymentAttemptStatus.PREPARED)
			.pgOrderId("ORD-1")
			.amount(12000L)
			.pgIdempotencyKey("confirm-1")
			.requestedAt(now)
			.nextCheckAt(now)
			.build();

		assertThat(attempt.isActive()).isTrue();
		attempt.markUnknown();
		attempt.scheduleNextCheck(now.plusMinutes(1));
		attempt.claim("lease-1", now.plusSeconds(30));

		assertThat(attempt.getAttemptStatus()).isEqualTo(PaymentAttemptStatus.UNKNOWN);
		assertThat(attempt.getCheckCount()).isEqualTo(1);
		assertThat(attempt.getLeaseToken()).isEqualTo("lease-1");
		assertThat(attempt.isActive()).isTrue();

		attempt.markFailed("REJECT_CARD_PAYMENT", "카드 승인이 거절되었습니다.");

		assertThat(attempt.getAttemptStatus()).isEqualTo(PaymentAttemptStatus.FAILED);
		assertThat(attempt.isActive()).isFalse();
	}

	@Test
	@DisplayName("웹훅 수신 기록은 처리 실패 후 재시도 상태로 전환된다")
	void reschedulesFailedWebhook() {
		LocalDateTime now = LocalDateTime.now();
		PaymentWebhookEvent event = PaymentWebhookEvent.builder()
			.transmissionId("transmission-1")
			.eventType("PAYMENT_STATUS_CHANGED")
			.eventHash("hash-1")
			.pgOrderId("ORD-1")
			.paymentKey("payment-key")
			.pgStatus("DONE")
			.processingStatus(PaymentWebhookProcessingStatus.RECEIVED)
			.nextProcessAt(now)
			.build();

		event.markProcessing("lease-1", now.plusSeconds(30));
		event.scheduleRetry(now.plusMinutes(1), "PG 조회 실패");

		assertThat(event.getProcessingStatus()).isEqualTo(PaymentWebhookProcessingStatus.RETRY_WAIT);
		assertThat(event.getReceiveCount()).isEqualTo(1);
		assertThat(event.getFailureReason()).isEqualTo("PG 조회 실패");

		event.markProcessed();

		assertThat(event.getProcessingStatus()).isEqualTo(PaymentWebhookProcessingStatus.PROCESSED);
		assertThat(event.getLeaseToken()).isNull();
	}

	private Payment payment(PurchaseOrder order, PaymentStatus status) {
		return Payment.builder()
			.paymentId("PAY-1")
			.order(order)
			.amount(12000L)
			.paymentMethod(PaymentMethod.CARD)
			.paymentStatus(status)
			.build();
	}
}
