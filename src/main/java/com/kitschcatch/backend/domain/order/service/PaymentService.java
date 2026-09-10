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
		// 외부 호출 또는 DB 저장 실패 시 결과가 불확실하므로 PROCESSING과 예약을 유지한다.
		TossPaymentResponse tossResponse = tossPaymentsClient.confirm(new TossPaymentConfirmRequest(
			context.paymentKey(), context.orderId(), context.amount()
		));
		validateTossPayment(tossResponse, context, TOSS_CONFIRM_DONE);
		return paymentTransactionService.completeConfirm(userId, paymentId, tossResponse.paymentKey());
	}

	public PaymentResponse getPayment(Long userId, String paymentId) {
		return paymentTransactionService.getPayment(userId, paymentId);
	}

	public PaymentResponse cancelPayment(Long userId, String paymentId) {
		PaymentOperationContext context = paymentTransactionService.startCancel(userId, paymentId);
		TossPaymentResponse tossResponse = tossPaymentsClient.cancel(new TossPaymentCancelRequest(
			context.paymentKey(), "고객 요청"
		));
		validateTossPayment(tossResponse, context, TOSS_CANCEL_CANCELED);
		return paymentTransactionService.completeCancel(userId, paymentId);
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
}
