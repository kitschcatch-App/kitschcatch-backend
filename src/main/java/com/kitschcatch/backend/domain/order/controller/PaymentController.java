// 결제 생성, 승인, 조회, 취소 HTTP API를 처리하는 컨트롤러
package com.kitschcatch.backend.domain.order.controller;

import com.kitschcatch.backend.domain.order.dto.ConfirmPaymentRequest;
import com.kitschcatch.backend.domain.order.dto.CreatePaymentRequest;
import com.kitschcatch.backend.domain.order.dto.CreatePaymentResponse;
import com.kitschcatch.backend.domain.order.dto.PaymentResponse;
import com.kitschcatch.backend.domain.order.service.PaymentService;
import com.kitschcatch.backend.global.response.ApiResponse;
import com.kitschcatch.backend.global.security.AuthenticatedUser;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/payments")
public class PaymentController {

	private final PaymentService paymentService;

	public PaymentController(PaymentService paymentService) {
		this.paymentService = paymentService;
	}

	@PostMapping
	public ApiResponse<CreatePaymentResponse> createPayment(
		@AuthenticationPrincipal AuthenticatedUser user,
		@Valid @RequestBody CreatePaymentRequest request
	) {
		return ApiResponse.created(paymentService.createPayment(user.userId(), request));
	}

	@PostMapping("/{paymentId}/confirm")
	public ApiResponse<PaymentResponse> confirmPayment(
		@AuthenticationPrincipal AuthenticatedUser user,
		@PathVariable String paymentId,
		@Valid @RequestBody ConfirmPaymentRequest request
	) {
		return ApiResponse.success(paymentService.confirmPayment(user.userId(), paymentId, request));
	}

	@GetMapping("/{paymentId}")
	public ApiResponse<PaymentResponse> getPayment(
		@AuthenticationPrincipal AuthenticatedUser user,
		@PathVariable String paymentId
	) {
		return ApiResponse.success(paymentService.getPayment(user.userId(), paymentId));
	}

	@PostMapping("/{paymentId}/cancel")
	public ApiResponse<PaymentResponse> cancelPayment(
		@AuthenticationPrincipal AuthenticatedUser user,
		@PathVariable String paymentId
	) {
		return ApiResponse.success(paymentService.cancelPayment(user.userId(), paymentId));
	}
}
