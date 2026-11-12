// 결제 웹훅 수신함의 중복 제거와 처리 상태 저장을 검증하는 테스트
package com.kitschcatch.backend.domain.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.kitschcatch.backend.domain.order.dto.TossPaymentWebhookRequest;
import com.kitschcatch.backend.domain.order.entity.PaymentWebhookEvent;
import com.kitschcatch.backend.domain.order.entity.PaymentWebhookProcessingStatus;
import com.kitschcatch.backend.domain.order.repository.PaymentWebhookEventRepository;
import com.kitschcatch.backend.global.exception.BusinessException;
import com.kitschcatch.backend.global.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PaymentWebhookServiceTest {

	private PaymentWebhookEventRepository eventRepository;
	private PaymentWebhookService webhookService;

	@BeforeEach
	void setUp() {
		eventRepository = org.mockito.Mockito.mock(PaymentWebhookEventRepository.class);
		webhookService = new PaymentWebhookService(eventRepository);
	}

	@Test
	@DisplayName("지원하는 웹훅은 수신 상태로 저장한다")
	void receivesSupportedWebhook() {
		TossPaymentWebhookRequest request = request("PAYMENT_STATUS_CHANGED", "DONE");

		webhookService.receive("transmission-1", request);

		verify(eventRepository).insertIfAbsent(org.mockito.ArgumentMatchers.argThat(event ->
			event.getProcessingStatus() == PaymentWebhookProcessingStatus.RECEIVED
				&& event.getEventType().equals("PAYMENT_STATUS_CHANGED")
				&& event.getPgOrderId().equals("ORD-1")
				&& event.getPaymentKey().equals("key-1")
				&& event.getEventHash().length() == 64));
	}

	@Test
	@DisplayName("지원하지 않는 이벤트도 감사 기록을 남기고 무시한다")
	void ignoresUnsupportedEvent() {
		webhookService.receive("transmission-1", request("DEPOSIT_CALLBACK", "DONE"));

		verify(eventRepository).insertIfAbsent(org.mockito.ArgumentMatchers.argThat(event ->
			event.getProcessingStatus() == PaymentWebhookProcessingStatus.IGNORED));
	}

	@Test
	@DisplayName("전송 ID가 없으면 웹훅을 저장하지 않는다")
	void rejectsMissingTransmissionId() {
		assertThatThrownBy(() -> webhookService.receive(" ", request("PAYMENT_STATUS_CHANGED", "DONE")))
			.isInstanceOf(BusinessException.class)
			.extracting("errorCode")
			.isEqualTo(ErrorCode.PAYMENT_WEBHOOK_INVALID);
		verify(eventRepository, never()).insertIfAbsent(any(PaymentWebhookEvent.class));
	}

	private TossPaymentWebhookRequest request(String eventType, String status) {
		return new TossPaymentWebhookRequest(
			eventType,
			"2026-09-16T10:15:30+09:00",
			new TossPaymentWebhookRequest.TossPaymentWebhookData("key-1", "ORD-1", 12000L, 0L, status)
		);
	}
}
