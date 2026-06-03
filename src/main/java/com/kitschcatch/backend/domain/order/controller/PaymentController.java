// 결제 생성, 승인, 조회, 취소 HTTP API를 처리하는 컨트롤러
package com.kitschcatch.backend.domain.order.controller;

import com.kitschcatch.backend.domain.order.dto.ConfirmPaymentRequest;
import com.kitschcatch.backend.domain.order.dto.CreatePaymentRequest;
import com.kitschcatch.backend.domain.order.dto.CreatePaymentResponse;
import com.kitschcatch.backend.domain.order.dto.PaymentResponse;
import com.kitschcatch.backend.domain.order.service.PaymentService;
import com.kitschcatch.backend.global.response.ApiResponse;
import com.kitschcatch.backend.global.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
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
@Tag(name = "결제", description = "결제 생성, 승인, 조회, 취소 API")
@SecurityRequirement(name = "bearerAuth")
public class PaymentController {

	private final PaymentService paymentService;

	public PaymentController(PaymentService paymentService) {
		this.paymentService = paymentService;
	}

	@PostMapping
	@Operation(summary = "결제 생성", description = "주문 번호 기준으로 결제를 생성하고 결제 식별자와 초기 상태를 반환합니다.")
	public ApiResponse<CreatePaymentResponse> createPayment(
		@AuthenticationPrincipal AuthenticatedUser user,
		@Valid @RequestBody CreatePaymentRequest request
	) {
		return ApiResponse.created(paymentService.createPayment(user.userId(), request));
	}

	@PostMapping("/{paymentId}/confirm")
	@Operation(summary = "결제 승인", description = "외부 PG에서 받은 paymentKey로 결제를 승인하고 최신 결제 상태를 반환합니다.")
	public ApiResponse<PaymentResponse> confirmPayment(
		@AuthenticationPrincipal AuthenticatedUser user,
		@Parameter(description = "내부 결제 ID", example = "pay_123456")
		@PathVariable String paymentId,
		@Valid @RequestBody ConfirmPaymentRequest request
	) {
		return ApiResponse.success(paymentService.confirmPayment(user.userId(), paymentId, request));
	}

	@GetMapping("/{paymentId}")
	@Operation(summary = "결제 조회", description = "내부 결제 ID로 결제 금액, 주문 번호, 상태, 승인 시각을 조회합니다.")
	public ApiResponse<PaymentResponse> getPayment(
		@AuthenticationPrincipal AuthenticatedUser user,
		@Parameter(description = "내부 결제 ID", example = "pay_123456")
		@PathVariable String paymentId
	) {
		return ApiResponse.success(paymentService.getPayment(user.userId(), paymentId));
	}

	@PostMapping("/{paymentId}/cancel")
	@Operation(summary = "결제 취소", description = "결제 가능한 상태의 결제를 취소하고 최신 결제 상태를 반환합니다.")
	public ApiResponse<PaymentResponse> cancelPayment(
		@AuthenticationPrincipal AuthenticatedUser user,
		@Parameter(description = "내부 결제 ID", example = "pay_123456")
		@PathVariable String paymentId
	) {
		return ApiResponse.success(paymentService.cancelPayment(user.userId(), paymentId));
	}
}
