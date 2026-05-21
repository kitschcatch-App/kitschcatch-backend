package com.kitschcatch.backend.domain.payment.controller;

import com.kitschcatch.backend.domain.payment.dto.CancelPaymentRequest;
import com.kitschcatch.backend.domain.payment.dto.ConfirmPaymentRequest;
import com.kitschcatch.backend.domain.payment.dto.CreatePaymentRequest;
import com.kitschcatch.backend.domain.payment.dto.PaymentResponse;
import com.kitschcatch.backend.domain.payment.service.PaymentService;
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
	public ApiResponse<PaymentResponse> createPayment(
		@AuthenticationPrincipal AuthenticatedUser user,
		@Valid @RequestBody CreatePaymentRequest request
	) {
		return ApiResponse.created(paymentService.createPayment(user.userId(), request));
	}

	@PostMapping("/{paymentId}/confirm")
	public ApiResponse<PaymentResponse> confirmPayment(
		@AuthenticationPrincipal AuthenticatedUser user,
		@PathVariable Long paymentId,
		@Valid @RequestBody ConfirmPaymentRequest request
	) {
		return ApiResponse.success(paymentService.confirmPayment(user.userId(), paymentId, request));
	}

	@GetMapping("/{paymentId}")
	public ApiResponse<PaymentResponse> getPayment(
		@AuthenticationPrincipal AuthenticatedUser user,
		@PathVariable Long paymentId
	) {
		return ApiResponse.success(paymentService.getPayment(user.userId(), paymentId));
	}

	@PostMapping("/{paymentId}/cancel")
	public ApiResponse<PaymentResponse> cancelPayment(
		@AuthenticationPrincipal AuthenticatedUser user,
		@PathVariable Long paymentId,
		@Valid @RequestBody CancelPaymentRequest request
	) {
		return ApiResponse.success(paymentService.cancelPayment(user.userId(), paymentId, request));
	}
}
