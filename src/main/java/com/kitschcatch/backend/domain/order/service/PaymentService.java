// 토스 외부 API 호출과 결제 상태 변경 흐름을 조율하는 서비스
package com.kitschcatch.backend.domain.order.service;

import com.kitschcatch.backend.domain.order.dto.ConfirmPaymentRequest;
import com.kitschcatch.backend.domain.order.dto.CreatePaymentRequest;
import com.kitschcatch.backend.domain.order.dto.CreatePaymentResponse;
import com.kitschcatch.backend.domain.order.dto.PaymentResponse;
import com.kitschcatch.backend.domain.order.toss.TossPaymentCancelRequest;
import com.kitschcatch.backend.domain.order.toss.TossPaymentConfirmRequest;
import com.kitschcatch.backend.domain.order.toss.TossPaymentResponse;
import com.kitschcatch.backend.domain.order.toss.TossPaymentsClient;
import com.kitschcatch.backend.global.exception.BusinessException;
import com.kitschcatch.backend.global.exception.ErrorCode;
import org.springframework.stereotype.Service;

@Service
public class PaymentService {

	private static final String TOSS_CONFIRM_DONE = "DONE";
	private static final String TOSS_CANCEL_CANCELED = "CANCELED";

	private final PaymentTransactionService paymentTransactionService;
	private final TossPaymentsClient tossPaymentsClient;

	public PaymentService(
		PaymentTransactionService paymentTransactionService,
		TossPaymentsClient tossPaymentsClient
	) {
		this.paymentTransactionService = paymentTransactionService;
		this.tossPaymentsClient = tossPaymentsClient;
	}

	public CreatePaymentResponse createPayment(Long userId, CreatePaymentRequest request) {
		return paymentTransactionService.createPayment(userId, request);
	}

	public PaymentResponse confirmPayment(Long userId, String paymentId, ConfirmPaymentRequest request) {
		PaymentOperationContext context = paymentTransactionService.startConfirm(userId, paymentId, request);
		try {
			TossPaymentResponse tossResponse = tossPaymentsClient.confirm(new TossPaymentConfirmRequest(
				context.paymentKey(),
				context.orderId(),
				context.amount()
			));
			validateTossPayment(tossResponse, context, TOSS_CONFIRM_DONE);
			return paymentTransactionService.completeConfirm(userId, paymentId, tossResponse.paymentKey());
		} catch (RuntimeException exception) {
			restoreProcessing(userId, paymentId, context);
			throw exception;
		}
	}

	public PaymentResponse getPayment(Long userId, String paymentId) {
		return paymentTransactionService.getPayment(userId, paymentId);
	}

	public PaymentResponse cancelPayment(Long userId, String paymentId) {
		PaymentOperationContext context = paymentTransactionService.startCancel(userId, paymentId);
		try {
			TossPaymentResponse tossResponse = tossPaymentsClient.cancel(new TossPaymentCancelRequest(
				context.paymentKey(),
				"고객 요청"
			));
			validateTossPayment(tossResponse, context, TOSS_CANCEL_CANCELED);
			return paymentTransactionService.completeCancel(userId, paymentId);
		} catch (RuntimeException exception) {
			restoreProcessing(userId, paymentId, context);
			throw exception;
		}
	}

	private void validateTossPayment(
		TossPaymentResponse tossResponse,
		PaymentOperationContext context,
		String expectedStatus
	) {
		if (tossResponse == null
			|| !context.paymentKey().equals(tossResponse.paymentKey())
			|| !context.orderId().equals(tossResponse.orderId())
			|| !context.amount().equals(tossResponse.totalAmount())
			|| !expectedStatus.equals(tossResponse.status())) {
			throw new BusinessException(ErrorCode.TOSS_PAYMENTS_REQUEST_FAILED);
		}
	}

	private void restoreProcessing(Long userId, String paymentId, PaymentOperationContext context) {
		try {
			paymentTransactionService.restoreProcessing(userId, paymentId, context.rollbackStatus());
		} catch (RuntimeException ignored) {
			// 복구 실패가 원래 결제 오류를 가리지 않도록 원래 예외를 유지한다.
		}
	}
}
