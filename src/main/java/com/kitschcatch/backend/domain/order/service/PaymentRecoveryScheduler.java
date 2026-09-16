// 외부 PG 결제 결과를 주기적으로 조회해 결제 상태를 확정하는 워커
package com.kitschcatch.backend.domain.order.service;

import com.kitschcatch.backend.domain.order.entity.PaymentAttempt;
import com.kitschcatch.backend.domain.order.entity.PaymentAttemptStatus;
import com.kitschcatch.backend.domain.order.repository.PaymentAttemptRepository;
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
@ConditionalOnProperty(prefix = "app.payments.recovery", name = "enabled", havingValue = "true", matchIfMissing = true)
public class PaymentRecoveryScheduler {

	private static final Logger log = LoggerFactory.getLogger(PaymentRecoveryScheduler.class);

	private final PaymentAttemptRepository paymentAttemptRepository;
	private final TossPaymentsClient tossPaymentsClient;
	private final PaymentRecoveryService paymentRecoveryService;
	private final int batchSize;

	public PaymentRecoveryScheduler(
		PaymentAttemptRepository paymentAttemptRepository,
		TossPaymentsClient tossPaymentsClient,
		PaymentRecoveryService paymentRecoveryService,
		@Value("${app.payments.recovery.batch-size:100}") int batchSize
	) {
		this.paymentAttemptRepository = paymentAttemptRepository;
		this.tossPaymentsClient = tossPaymentsClient;
		this.paymentRecoveryService = paymentRecoveryService;
		this.batchSize = batchSize;
	}

	@Scheduled(
		fixedDelayString = "${app.payments.recovery.scan-delay:30s}",
		initialDelayString = "${app.payments.recovery.scan-delay:30s}"
	)
	public void recoverPayments() {
		List<PaymentAttempt> candidates = paymentAttemptRepository.findRecoveryCandidates(
			List.of(PaymentAttemptStatus.PROCESSING, PaymentAttemptStatus.UNKNOWN),
			LocalDateTime.now(),
			PageRequest.of(0, batchSize)
		);
		for (PaymentAttempt attempt : candidates) {
			String leaseToken = UUID.randomUUID().toString().replace("-", "").toUpperCase(Locale.ROOT);
			try {
				if (!paymentRecoveryService.claim(
					attempt.getAttemptId(), leaseToken, LocalDateTime.now().plusSeconds(45))) {
					continue;
				}
				TossPaymentResponse response = attempt.getPaymentKey() == null
					? tossPaymentsClient.getPaymentByOrderId(attempt.getPgOrderId())
					: tossPaymentsClient.getPayment(attempt.getPaymentKey());
				paymentRecoveryService.recover(attempt.getAttemptId(), response);
			} catch (RuntimeException exception) {
				log.warn("결제 결과 복구 조회 실패. attemptId={}", attempt.getAttemptId(), exception);
				try {
					paymentRecoveryService.recordLookupFailure(attempt.getAttemptId(), exception.getMessage());
				} catch (RuntimeException stateException) {
					log.warn("결제 복구 실패 상태 저장 실패. attemptId={}", attempt.getAttemptId(), stateException);
				}
			}
		}
	}
}
