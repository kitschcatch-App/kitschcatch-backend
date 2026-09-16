// 성공 결제의 외부 취소를 저빈도 순환 조회에 포함하는지 검증하는 테스트
package com.kitschcatch.backend.domain.order.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.kitschcatch.backend.domain.order.entity.PaymentAttempt;
import com.kitschcatch.backend.domain.order.entity.PaymentAttemptOperation;
import com.kitschcatch.backend.domain.order.entity.PaymentAttemptStatus;
import com.kitschcatch.backend.domain.order.repository.PaymentAttemptRepository;
import com.kitschcatch.backend.domain.order.toss.TossPaymentResponse;
import com.kitschcatch.backend.domain.order.toss.TossPaymentsClient;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

class PaymentRecoverySchedulerTest {

	@Test
	void scansOldSuccessfulApprovalForExternalCancellation() {
		PaymentAttemptRepository attemptRepository = mock(PaymentAttemptRepository.class);
		TossPaymentsClient tossPaymentsClient = mock(TossPaymentsClient.class);
		PaymentRecoveryService recoveryService = mock(PaymentRecoveryService.class);
		PaymentAttempt attempt = PaymentAttempt.builder().attemptId("ATT-1").sequenceNumber(1)
			.operation(PaymentAttemptOperation.CONFIRM).attemptStatus(PaymentAttemptStatus.SUCCEEDED)
			.pgOrderId("PG-1").paymentKey("key-1").amount(12000L).pgIdempotencyKey("confirm-1")
			.requestedAt(LocalDateTime.now().minusHours(2)).nextCheckAt(LocalDateTime.now()).build();
		when(attemptRepository.findSuccessfulRecoveryCandidates(any(LocalDateTime.class), any(Pageable.class)))
			.thenReturn(List.of(attempt));
		when(recoveryService.claim(anyString(), anyString(), any(LocalDateTime.class))).thenReturn(true);
		when(tossPaymentsClient.getPayment("key-1"))
			.thenReturn(new TossPaymentResponse("key-1", "PG-1", 12000L, "CANCELED", 0L,
				null, "2026-09-16T10:20:30+09:00", "tx-1"));
		PaymentRecoveryScheduler scheduler = new PaymentRecoveryScheduler(attemptRepository, tossPaymentsClient,
			recoveryService, 10);

		scheduler.scanSuccessfulPayments();

		verify(tossPaymentsClient).getPayment("key-1");
		verify(recoveryService).recover(anyString(), any(TossPaymentResponse.class), anyString());
	}
}
