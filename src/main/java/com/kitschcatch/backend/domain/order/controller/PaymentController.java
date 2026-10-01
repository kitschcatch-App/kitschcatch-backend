// 결제 생성, 승인, 조회, 취소 HTTP API를 처리하는 컨트롤러
package com.kitschcatch.backend.domain.order.controller;

import com.kitschcatch.backend.domain.order.dto.ConfirmPaymentRequest;
import com.kitschcatch.backend.domain.order.dto.CreatePaymentRequest;
import com.kitschcatch.backend.domain.order.dto.CreatePaymentResponse;
import com.kitschcatch.backend.domain.order.dto.PaymentResponse;
import com.kitschcatch.backend.domain.order.dto.RetryPaymentRequest;
import com.kitschcatch.backend.domain.order.dto.RetryPaymentResponse;
import com.kitschcatch.backend.domain.order.service.PaymentService;
import com.kitschcatch.backend.domain.order.entity.PaymentStatus;
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
	@Operation(summary = "결제 승인", description = "최초 시도는 attemptId 생략이 가능합니다. 같은 요청은 PG를 재호출하지 않습니다. 결과가 불확실하거나 처리 중이면 202와 PROCESSING을 반환하며 결제 조회를 반복해야 합니다. 확정 거절은 200과 FAILED로 반환합니다.")
	public ApiResponse<PaymentResponse> confirmPayment(
		@AuthenticationPrincipal AuthenticatedUser user,
		@Parameter(description = "내부 결제 ID", example = "pay_123456")
		@PathVariable String paymentId,
		@Valid @RequestBody ConfirmPaymentRequest request
	) {
		return operationResponse(paymentService.confirmPayment(user.userId(), paymentId, request));
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

	@PostMapping("/{paymentId}/retry")
	@Operation(summary = "결제 재시도 준비", description = "실패한 결제를 재시도할 수 있도록 새 결제 시도와 PG 주문 번호를 발급합니다.")
	public ApiResponse<RetryPaymentResponse> prepareRetry(
		@AuthenticationPrincipal AuthenticatedUser user,
		@Parameter(description = "내부 결제 ID", example = "pay_123456")
		@PathVariable String paymentId,
		@Valid @RequestBody RetryPaymentRequest request
	) {
		return ApiResponse.success(paymentService.prepareRetry(user.userId(), paymentId, request));
	}

	@PostMapping("/{paymentId}/cancel")
	@Operation(summary = "결제 취소", description = "같은 취소 재요청은 PG를 재호출하지 않습니다. 처리 중·결과 불확실은 202, 전액 취소 완료는 200으로 현재 상태를 반환합니다.")
	public ApiResponse<PaymentResponse> cancelPayment(
		@AuthenticationPrincipal AuthenticatedUser user,
		@Parameter(description = "내부 결제 ID", example = "pay_123456")
		@PathVariable String paymentId
	) {
		return operationResponse(paymentService.cancelPayment(user.userId(), paymentId));
	}

	private ApiResponse<PaymentResponse> operationResponse(PaymentResponse response) {
		return response.status() == PaymentStatus.PROCESSING
			? ApiResponse.accepted(response) : ApiResponse.success(response);
	}
}
