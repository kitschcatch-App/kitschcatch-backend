// 저장된 Toss 결제 웹훅을 PG 재조회와 공통 복구 규칙으로 처리하는 워커
package com.kitschcatch.backend.domain.order.service;

import com.kitschcatch.backend.domain.order.entity.PaymentAttempt;
import com.kitschcatch.backend.domain.order.entity.PaymentWebhookEvent;
import com.kitschcatch.backend.domain.order.repository.PaymentAttemptRepository;
import com.kitschcatch.backend.domain.order.repository.PaymentWebhookEventRepository;
import com.kitschcatch.backend.domain.order.toss.TossPaymentResponse;
import com.kitschcatch.backend.domain.order.toss.TossPaymentsClient;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "app.payments.recovery", name = "enabled", havingValue = "true")
public class PaymentWebhookProcessingService {

	private static final Logger log = LoggerFactory.getLogger(PaymentWebhookProcessingService.class);

	private final PaymentWebhookEventRepository eventRepository;
	private final PaymentAttemptRepository attemptRepository;
	private final PaymentWebhookService webhookService;
	private final PaymentRecoveryService recoveryService;
	private final TossPaymentsClient tossPaymentsClient;
	private final int batchSize;

	public PaymentWebhookProcessingService(
		PaymentWebhookEventRepository eventRepository,
		PaymentAttemptRepository attemptRepository,
		PaymentWebhookService webhookService,
		PaymentRecoveryService recoveryService,
		TossPaymentsClient tossPaymentsClient,
		@Value("${app.payments.recovery.batch-size:100}") int batchSize
	) {
		this.eventRepository = eventRepository;
		this.attemptRepository = attemptRepository;
		this.webhookService = webhookService;
		this.recoveryService = recoveryService;
		this.tossPaymentsClient = tossPaymentsClient;
		this.batchSize = batchSize;
	}

	@Scheduled(
		fixedDelayString = "${app.payments.recovery.scan-delay:30s}",
		initialDelayString = "${app.payments.recovery.scan-delay:30s}"
	)
	public void processWebhooks() {
		List<PaymentWebhookEvent> candidates = eventRepository.findProcessingCandidates(
			List.of(
				com.kitschcatch.backend.domain.order.entity.PaymentWebhookProcessingStatus.RECEIVED,
				com.kitschcatch.backend.domain.order.entity.PaymentWebhookProcessingStatus.RETRY_WAIT,
				com.kitschcatch.backend.domain.order.entity.PaymentWebhookProcessingStatus.PROCESSING
			), LocalDateTime.now(), PageRequest.of(0, batchSize));
		for (PaymentWebhookEvent candidate : candidates) {
			String token = UUID.randomUUID().toString().replace("-", "").toUpperCase(Locale.ROOT);
			try {
				PaymentWebhookEvent event = webhookService.claim(candidate.getId(), token, LocalDateTime.now().plusSeconds(45));
				if (event == null) {
					continue;
				}
				TossPaymentResponse response = event.getPaymentKey() == null
					? tossPaymentsClient.getPaymentByOrderId(event.getPgOrderId())
					: tossPaymentsClient.getPayment(event.getPaymentKey());
				PaymentAttempt attempt = attemptRepository.findFirstByPaymentKeyOrPgOrderIdOrderBySequenceNumberDesc(
					event.getPaymentKey(), event.getPgOrderId()).orElse(null);
				if (attempt == null) {
					webhookService.markProcessed(event.getId());
					continue;
				}
				recoveryService.recover(attempt.getAttemptId(), response);
				webhookService.markProcessed(event.getId());
			} catch (RuntimeException exception) {
				log.warn("결제 웹훅 처리 실패. eventId={}", candidate.getId(), exception);
				webhookService.scheduleRetry(candidate.getId(), exception.getMessage());
			}
		}
	}
}
