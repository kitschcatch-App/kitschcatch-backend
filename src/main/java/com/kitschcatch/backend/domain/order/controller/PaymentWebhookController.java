// Toss 결제 상태 변경 웹훅 수신 HTTP API를 처리하는 컨트롤러
package com.kitschcatch.backend.domain.order.controller;

import com.kitschcatch.backend.domain.order.dto.TossPaymentWebhookRequest;
import com.kitschcatch.backend.domain.order.service.PaymentWebhookService;
import com.kitschcatch.backend.global.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/payments/webhooks")
@Tag(name = "결제 웹훅", description = "Toss 결제 상태 변경 웹훅 API")
public class PaymentWebhookController {

	private static final String TRANSMISSION_ID_HEADER = "tosspayments-webhook-transmission-id";

	private final PaymentWebhookService paymentWebhookService;

	public PaymentWebhookController(PaymentWebhookService paymentWebhookService) {
		this.paymentWebhookService = paymentWebhookService;
	}

	@PostMapping("/toss")
	@Operation(summary = "Toss 결제 웹훅 수신", description = "Toss 결제 상태 변경 이벤트를 수신함에 저장합니다.")
	public ApiResponse<Void> receiveTossWebhook(
		@RequestHeader(value = TRANSMISSION_ID_HEADER, required = false) String transmissionId,
		@Valid @RequestBody TossPaymentWebhookRequest request
	) {
		paymentWebhookService.receive(transmissionId, request);
		return ApiResponse.success(null);
	}
}
