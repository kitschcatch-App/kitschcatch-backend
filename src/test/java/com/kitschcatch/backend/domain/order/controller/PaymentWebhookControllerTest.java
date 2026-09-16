// 결제 웹훅 수신 HTTP API의 요청과 공통 응답을 검증하는 테스트
package com.kitschcatch.backend.domain.order.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.kitschcatch.backend.domain.order.dto.TossPaymentWebhookRequest;
import com.kitschcatch.backend.domain.order.service.PaymentWebhookService;
import com.kitschcatch.backend.global.exception.GlobalExceptionHandler;
import com.kitschcatch.backend.global.exception.BusinessException;
import com.kitschcatch.backend.global.exception.ErrorCode;
import com.kitschcatch.backend.global.response.ResponseStatusSetterAdvice;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class PaymentWebhookControllerTest {

	private PaymentWebhookService webhookService;
	private MockMvc mockMvc;

	@BeforeEach
	void setUp() {
		webhookService = mock(PaymentWebhookService.class);
		mockMvc = MockMvcBuilders.standaloneSetup(new PaymentWebhookController(webhookService))
			.setControllerAdvice(new ResponseStatusSetterAdvice(), new GlobalExceptionHandler())
			.build();
	}

	@Test
	@DisplayName("Toss 웹훅은 전송 ID와 본문을 서비스에 전달하고 200을 반환한다")
	void receivesTossWebhook() throws Exception {
		mockMvc.perform(post("/api/payments/webhooks/toss")
				.header("tosspayments-webhook-transmission-id", "transmission-1")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "eventType": "PAYMENT_STATUS_CHANGED",
					  "createdAt": "2026-09-16T10:15:30+09:00",
					  "data": {
					    "paymentKey": "key-1",
					    "orderId": "ORD-1",
					    "totalAmount": 12000,
					    "balanceAmount": 0,
					    "status": "DONE"
					  }
					}
					"""))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true));

		verify(webhookService).receive(eq("transmission-1"), any(TossPaymentWebhookRequest.class));
	}

	@Test
	@DisplayName("웹훅 전송 ID가 없으면 400을 반환한다")
	void rejectsMissingTransmissionId() throws Exception {
		doThrow(new BusinessException(ErrorCode.PAYMENT_WEBHOOK_INVALID))
			.when(webhookService).receive(isNull(), any(TossPaymentWebhookRequest.class));

		mockMvc.perform(post("/api/payments/webhooks/toss")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "eventType": "PAYMENT_STATUS_CHANGED",
					  "createdAt": "2026-09-16T10:15:30+09:00",
					  "data": {"orderId": "ORD-1", "status": "DONE"}
					}
					"""))
			.andExpect(status().isBadRequest());
	}
}
