// 결제 웹훅 수신함의 중복 제거와 처리 상태 저장을 검증하는 테스트
package com.kitschcatch.backend.domain.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.kitschcatch.backend.domain.order.dto.TossPaymentWebhookRequest;
import com.kitschcatch.backend.domain.order.entity.PaymentWebhookEvent;
import com.kitschcatch.backend.domain.order.entity.PaymentWebhookProcessingStatus;
import com.kitschcatch.backend.domain.order.repository.PaymentWebhookEventRepository;
import com.kitschcatch.backend.global.exception.BusinessException;
import com.kitschcatch.backend.global.exception.ErrorCode;
import java.util.Optional;
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
		when(eventRepository.findByTransmissionId(any())).thenReturn(Optional.empty());
		when(eventRepository.findByEventTypeAndEventHash(any(), any())).thenReturn(Optional.empty());
	}

	@Test
	@DisplayName("지원하는 웹훅은 수신 상태로 저장한다")
	void receivesSupportedWebhook() {
		TossPaymentWebhookRequest request = request("PAYMENT_STATUS_CHANGED", "DONE");

		webhookService.receive("transmission-1", request);

		verify(eventRepository).save(org.mockito.ArgumentMatchers.argThat(event ->
			event.getProcessingStatus() == PaymentWebhookProcessingStatus.RECEIVED
				&& event.getEventType().equals("PAYMENT_STATUS_CHANGED")
				&& event.getPgOrderId().equals("ORD-1")
				&& event.getPaymentKey().equals("key-1")));
	}

	@Test
	@DisplayName("같은 전송 ID의 웹훅은 한 번만 저장한다")
	void ignoresDuplicateTransmission() {
		when(eventRepository.findByTransmissionId("transmission-1"))
			.thenReturn(Optional.of(org.mockito.Mockito.mock(PaymentWebhookEvent.class)));

		webhookService.receive("transmission-1", request("PAYMENT_STATUS_CHANGED", "DONE"));

		verify(eventRepository, never()).save(any(PaymentWebhookEvent.class));
	}

	@Test
	@DisplayName("지원하지 않는 이벤트도 감사 기록을 남기고 무시한다")
	void ignoresUnsupportedEvent() {
		webhookService.receive("transmission-1", request("DEPOSIT_CALLBACK", "DONE"));

		verify(eventRepository).save(org.mockito.ArgumentMatchers.argThat(event ->
			event.getProcessingStatus() == PaymentWebhookProcessingStatus.IGNORED));
	}

	@Test
	@DisplayName("전송 ID가 없으면 웹훅을 저장하지 않는다")
	void rejectsMissingTransmissionId() {
		assertThatThrownBy(() -> webhookService.receive(" ", request("PAYMENT_STATUS_CHANGED", "DONE")))
			.isInstanceOf(BusinessException.class)
			.extracting("errorCode")
			.isEqualTo(ErrorCode.PAYMENT_WEBHOOK_INVALID);
		verify(eventRepository, never()).save(any(PaymentWebhookEvent.class));
	}

	@Test
	@DisplayName("같은 본문의 재전송은 전송 ID가 달라도 중복 저장하지 않는다")
	void ignoresDuplicatePayload() {
		webhookService.receive("transmission-1", request("PAYMENT_STATUS_CHANGED", "DONE"));
		PaymentWebhookEvent existing = org.mockito.Mockito.mock(PaymentWebhookEvent.class);
		when(eventRepository.findByEventTypeAndEventHash("PAYMENT_STATUS_CHANGED", "ignored"))
			.thenReturn(Optional.of(existing));

		// 해시 계산 결과를 모르는 구현 세부사항에 의존하지 않고, 저장된 첫 이벤트의 해시를 재사용해 중복을 검증한다.
		org.mockito.ArgumentCaptor<PaymentWebhookEvent> captor = org.mockito.ArgumentCaptor.forClass(PaymentWebhookEvent.class);
		verify(eventRepository).save(captor.capture());
		when(eventRepository.findByEventTypeAndEventHash("PAYMENT_STATUS_CHANGED", captor.getValue().getEventHash()))
			.thenReturn(Optional.of(existing));

		webhookService.receive("transmission-2", request("PAYMENT_STATUS_CHANGED", "DONE"));

		verify(eventRepository, org.mockito.Mockito.times(1)).save(any(PaymentWebhookEvent.class));
		assertThat(captor.getValue().getEventHash()).hasSize(64);
	}

	private TossPaymentWebhookRequest request(String eventType, String status) {
		return new TossPaymentWebhookRequest(
			eventType,
			"2026-09-16T10:15:30+09:00",
			new TossPaymentWebhookRequest.TossPaymentWebhookData("key-1", "ORD-1", 12000L, 0L, status)
		);
	}
}
