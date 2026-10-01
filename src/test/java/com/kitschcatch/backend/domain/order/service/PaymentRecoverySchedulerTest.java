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
import org.mockito.ArgumentCaptor;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;

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
		when(attemptRepository.findSuccessfulRecoveryCandidates(any(LocalDateTime.class), any(LocalDateTime.class), any(Pageable.class)))
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

	@Test
	void computesLeaseFromEachCandidatesProcessingTimeInsteadOfBatchStart() {
		var repository = mock(PaymentAttemptRepository.class);
		var pg = mock(TossPaymentsClient.class);
		var recovery = mock(PaymentRecoveryService.class);
		PaymentAttempt first = successfulAttempt("ATT-1", "key-1");
		PaymentAttempt second = successfulAttempt("ATT-2", "key-2");
		when(repository.findSuccessfulRecoveryCandidates(any(), any(), any())).thenReturn(List.of(first, second));
		when(recovery.claim(anyString(), anyString(), any())).thenReturn(true);
		AtomicReference<LocalDateTime> firstLookupFinishedAt = new AtomicReference<>();
		when(pg.getPayment("key-1")).thenAnswer(invocation -> {
			firstLookupFinishedAt.set(LocalDateTime.now());
			return new TossPaymentResponse("key-1", "PG-ATT-1", 12000L, "DONE");
		});
		when(pg.getPayment("key-2")).thenReturn(new TossPaymentResponse("key-2", "PG-ATT-2", 12000L, "DONE"));
		new PaymentRecoveryScheduler(repository, pg, recovery, 10).scanSuccessfulPayments();
		var expiry = ArgumentCaptor.forClass(LocalDateTime.class);
		verify(recovery, times(2)).claim(anyString(), anyString(), expiry.capture());
		assertThat(expiry.getAllValues().get(1)).isAfterOrEqualTo(firstLookupFinishedAt.get().plusSeconds(45));
	}

	private PaymentAttempt successfulAttempt(String id, String key) {
		return PaymentAttempt.builder().attemptId(id).sequenceNumber(1)
			.operation(PaymentAttemptOperation.CONFIRM).attemptStatus(PaymentAttemptStatus.SUCCEEDED)
			.pgOrderId("PG-" + id).paymentKey(key).amount(12000L).pgIdempotencyKey("confirm-" + id)
			.requestedAt(LocalDateTime.now().minusHours(2)).nextCheckAt(LocalDateTime.now()).build();
	}
}
