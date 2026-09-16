// 저장된 결제 웹훅을 PG 재조회와 공통 복구 서비스로 넘기는지 검증하는 테스트
package com.kitschcatch.backend.domain.order.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.kitschcatch.backend.domain.order.entity.PaymentAttempt;
import com.kitschcatch.backend.domain.order.entity.PaymentAttemptOperation;
import com.kitschcatch.backend.domain.order.entity.PaymentAttemptStatus;
import com.kitschcatch.backend.domain.order.entity.PaymentWebhookEvent;
import com.kitschcatch.backend.domain.order.entity.PaymentWebhookProcessingStatus;
import com.kitschcatch.backend.domain.order.repository.PaymentAttemptRepository;
import com.kitschcatch.backend.domain.order.repository.PaymentWebhookEventRepository;
import com.kitschcatch.backend.domain.order.toss.TossPaymentResponse;
import com.kitschcatch.backend.domain.order.toss.TossPaymentsClient;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.data.domain.Pageable;

class PaymentWebhookProcessingServiceTest {

	@Test
	void processesWebhookByFetchingAuthoritativePgResult() {
		PaymentWebhookEventRepository eventRepository = mock(PaymentWebhookEventRepository.class);
		PaymentAttemptRepository attemptRepository = mock(PaymentAttemptRepository.class);
		TossPaymentsClient tossPaymentsClient = mock(TossPaymentsClient.class);
		PaymentRecoveryService recoveryService = mock(PaymentRecoveryService.class);
		PaymentWebhookService webhookService = new PaymentWebhookService(eventRepository);
		PaymentWebhookProcessingService worker = new PaymentWebhookProcessingService(
			eventRepository, attemptRepository, webhookService, recoveryService, tossPaymentsClient, 10);
		PaymentWebhookEvent event = PaymentWebhookEvent.builder()
			.transmissionId("tx-1").eventType("PAYMENT_STATUS_CHANGED").eventHash("hash")
			.pgOrderId("ORD-1").paymentKey("key-1").pgStatus("DONE")
			.processingStatus(PaymentWebhookProcessingStatus.RECEIVED).nextProcessAt(LocalDateTime.now()).build();
		ReflectionTestUtils.setField(event, "id", 1L);
		PaymentAttempt attempt = PaymentAttempt.builder().attemptId("ATT-1").sequenceNumber(1)
			.operation(PaymentAttemptOperation.CONFIRM).attemptStatus(PaymentAttemptStatus.UNKNOWN)
			.pgOrderId("ORD-1").paymentKey("key-1").amount(12000L).pgIdempotencyKey("confirm-key")
			.requestedAt(LocalDateTime.now()).nextCheckAt(LocalDateTime.now()).build();
		when(eventRepository.findProcessingCandidates(any(), any(), any(Pageable.class))).thenReturn(List.of(event));
		when(eventRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(event));
		when(attemptRepository.findFirstByPaymentKeyOrPgOrderIdOrderBySequenceNumberDesc("key-1", "ORD-1"))
			.thenReturn(Optional.of(attempt));
		when(tossPaymentsClient.getPayment("key-1"))
			.thenReturn(new TossPaymentResponse("key-1", "ORD-1", 12000L, "DONE"));

		worker.processWebhooks();

		verify(tossPaymentsClient).getPayment("key-1");
		verify(recoveryService).recover("ATT-1", new TossPaymentResponse("key-1", "ORD-1", 12000L, "DONE"));
		org.mockito.Mockito.verify(eventRepository, org.mockito.Mockito.times(2)).findByIdForUpdate(1L);
	}
}
