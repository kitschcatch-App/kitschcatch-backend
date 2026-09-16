// Toss 결제 웹훅을 중복 없이 수신함에 저장하는 서비스
package com.kitschcatch.backend.domain.order.service;

import com.kitschcatch.backend.domain.order.dto.TossPaymentWebhookRequest;
import com.kitschcatch.backend.domain.order.entity.PaymentWebhookEvent;
import com.kitschcatch.backend.domain.order.entity.PaymentWebhookProcessingStatus;
import com.kitschcatch.backend.domain.order.repository.PaymentWebhookEventRepository;
import com.kitschcatch.backend.global.exception.BusinessException;
import com.kitschcatch.backend.global.exception.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class PaymentWebhookService {

	private static final String PAYMENT_STATUS_CHANGED = "PAYMENT_STATUS_CHANGED";

	private final PaymentWebhookEventRepository eventRepository;

	public PaymentWebhookService(PaymentWebhookEventRepository eventRepository) {
		this.eventRepository = eventRepository;
	}

	@Transactional
	public void receive(String transmissionId, TossPaymentWebhookRequest request) {
		if (!StringUtils.hasText(transmissionId) || request == null
			|| !StringUtils.hasText(request.eventType())
			|| !StringUtils.hasText(request.createdAt()) || request.data() == null) {
			throw new BusinessException(ErrorCode.PAYMENT_WEBHOOK_INVALID);
		}

		String eventHash = eventHash(request);
		if (eventRepository.findByTransmissionId(transmissionId).isPresent()
			|| eventRepository.findByEventTypeAndEventHash(request.eventType(), eventHash).isPresent()) {
			return;
		}

		TossPaymentWebhookRequest.TossPaymentWebhookData data = request.data();
		boolean supported = PAYMENT_STATUS_CHANGED.equals(request.eventType());
		PaymentWebhookEvent event = PaymentWebhookEvent.builder()
			.transmissionId(transmissionId)
			.eventType(request.eventType())
			.eventHash(eventHash)
			.pgOrderId(data.orderId())
			.paymentKey(data.paymentKey())
			.pgStatus(data.status())
			.pgCreatedAt(parseCreatedAt(request.createdAt()))
			.processingStatus(supported ? PaymentWebhookProcessingStatus.RECEIVED : PaymentWebhookProcessingStatus.IGNORED)
			.nextProcessAt(LocalDateTime.now())
			.build();
		if (!supported) {
			event.ignore("지원하지 않는 웹훅 이벤트입니다.");
		}
		eventRepository.save(event);
	}

	private String eventHash(TossPaymentWebhookRequest request) {
		TossPaymentWebhookRequest.TossPaymentWebhookData data = request.data();
		String canonical = String.join("|",
			request.eventType(), request.createdAt(),
			value(data.paymentKey()), value(data.orderId()), value(data.totalAmount()),
			value(data.balanceAmount()), value(data.status()));
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
				.digest(canonical.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256을 사용할 수 없습니다.", exception);
		}
	}

	private String value(Object value) {
		return value == null ? "" : value.toString();
	}

	private LocalDateTime parseCreatedAt(String createdAt) {
		try {
			return java.time.OffsetDateTime.parse(createdAt).toLocalDateTime();
		} catch (java.time.format.DateTimeParseException exception) {
			try {
				return LocalDateTime.parse(createdAt);
			} catch (java.time.format.DateTimeParseException ignored) {
				throw new BusinessException(ErrorCode.PAYMENT_WEBHOOK_INVALID, "웹훅 생성 시각이 올바르지 않습니다.");
			}
		}
	}
}
