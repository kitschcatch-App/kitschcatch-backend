// 토스 외부 API 호출과 결제 상태 변경 흐름을 조율하는 서비스
package com.kitschcatch.backend.domain.order.service;

import com.kitschcatch.backend.domain.order.dto.ConfirmPaymentRequest;
import com.kitschcatch.backend.domain.order.dto.CreatePaymentRequest;
import com.kitschcatch.backend.domain.order.dto.CreatePaymentResponse;
import com.kitschcatch.backend.domain.order.dto.PaymentResponse;
import com.kitschcatch.backend.domain.order.dto.RetryPaymentRequest;
import com.kitschcatch.backend.domain.order.dto.RetryPaymentResponse;
import com.kitschcatch.backend.domain.order.toss.TossPaymentCancelRequest;
import com.kitschcatch.backend.domain.order.toss.TossPaymentConfirmRequest;
import com.kitschcatch.backend.domain.order.toss.TossPaymentResponse;
import com.kitschcatch.backend.domain.order.toss.TossPaymentsClient;
import com.kitschcatch.backend.domain.order.toss.TossPaymentException;
import com.kitschcatch.backend.global.exception.BusinessException;
import com.kitschcatch.backend.global.exception.ErrorCode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class PaymentService {

	private static final String TOSS_CONFIRM_DONE = "DONE";
	private static final String TOSS_CANCEL_CANCELED = "CANCELED";

	private final PaymentTransactionService paymentTransactionService;
	private final TossPaymentsClient tossPaymentsClient;
	private final PaymentRecoveryService paymentRecoveryService;

	public PaymentService(
		PaymentTransactionService paymentTransactionService,
		TossPaymentsClient tossPaymentsClient
	) {
		this(paymentTransactionService, tossPaymentsClient, null);
	}

	@Autowired
	public PaymentService(
		PaymentTransactionService paymentTransactionService,
		TossPaymentsClient tossPaymentsClient,
		PaymentRecoveryService paymentRecoveryService
	) {
		this.paymentTransactionService = paymentTransactionService;
		this.tossPaymentsClient = tossPaymentsClient;
		this.paymentRecoveryService = paymentRecoveryService;
	}

	public CreatePaymentResponse createPayment(Long userId, CreatePaymentRequest request) {
		return paymentTransactionService.createPayment(userId, request);
	}

	public PaymentResponse confirmPayment(Long userId, String paymentId, ConfirmPaymentRequest request) {
		PaymentOperationContext context = paymentTransactionService.startConfirm(userId, paymentId, request);
		if (!context.executePg()) return getPayment(userId, paymentId);
		// 외부 호출 또는 DB 저장 실패 시 결과가 불확실하므로 PROCESSING과 예약을 유지한다.
		TossPaymentConfirmRequest confirmRequest = context.pgIdempotencyKey() == null
			? new TossPaymentConfirmRequest(context.paymentKey(), context.pgOrderId(), context.amount())
			: new TossPaymentConfirmRequest(context.paymentKey(), context.pgOrderId(), context.amount(), context.pgIdempotencyKey());
		TossPaymentResponse tossResponse;
		try {
			tossResponse = tossPaymentsClient.confirm(confirmRequest);
		} catch (BusinessException exception) {
			return handlePgFailure(userId, paymentId, context, exception);
		}
		if (paymentRecoveryService != null && context.attemptId() != null) {
			return paymentRecoveryService.recover(context.attemptId(), tossResponse);
		}
		validateTossPayment(tossResponse, context, TOSS_CONFIRM_DONE);
		return paymentTransactionService.completeConfirm(userId, paymentId, tossResponse.paymentKey());
	}

	public PaymentResponse getPayment(Long userId, String paymentId) {
		return paymentTransactionService.getPayment(userId, paymentId);
	}

	public RetryPaymentResponse prepareRetry(Long userId, String paymentId, RetryPaymentRequest request) {
		return paymentTransactionService.prepareRetry(userId, paymentId, request);
	}

	public PaymentResponse cancelPayment(Long userId, String paymentId) {
		PaymentOperationContext context = paymentTransactionService.startCancel(userId, paymentId);
		return executeCancellation(userId, paymentId, context, "고객 요청");
	}

	public PaymentResponse executeCancellation(Long userId, String paymentId, PaymentOperationContext context, String reason) {
		if (!context.executePg()) return getPayment(userId, paymentId);
		TossPaymentCancelRequest cancelRequest = context.pgIdempotencyKey() == null
			? new TossPaymentCancelRequest(context.paymentKey(), reason)
			: new TossPaymentCancelRequest(context.paymentKey(), reason, context.pgIdempotencyKey());
		TossPaymentResponse tossResponse;
		try {
			tossResponse = tossPaymentsClient.cancel(cancelRequest);
		} catch (BusinessException exception) {
			return handlePgFailure(userId, paymentId, context, exception);
		}
		if (paymentRecoveryService != null && context.attemptId() != null) {
			return paymentRecoveryService.recover(context.attemptId(), tossResponse);
		}
		validateTossPayment(tossResponse, context, TOSS_CANCEL_CANCELED);
		return paymentTransactionService.completeCancel(userId, paymentId);
	}

	private PaymentResponse handlePgFailure(Long userId, String paymentId, PaymentOperationContext context,
		BusinessException exception) {
		if (paymentRecoveryService == null || context.attemptId() == null) throw exception;
		if (exception instanceof TossPaymentException pg && pg.confirmedRejection()) {
			paymentRecoveryService.recordConfirmedRejection(context.attemptId(), pg.pgCode());
		} else {
			String code = exception instanceof TossPaymentException pg ? pg.pgCode() : exception.getErrorCode().name();
			paymentRecoveryService.recordLookupFailure(context.attemptId(), code);
		}
		return getPayment(userId, paymentId);
	}

	private void validateTossPayment(
		TossPaymentResponse tossResponse,
		PaymentOperationContext context,
		String expectedStatus
	) {
		if (tossResponse == null
			|| !context.paymentKey().equals(tossResponse.paymentKey())
			|| !context.pgOrderId().equals(tossResponse.orderId())
			|| !context.amount().equals(tossResponse.totalAmount())
			|| !expectedStatus.equals(tossResponse.status())) {
			throw new BusinessException(ErrorCode.TOSS_PAYMENTS_REQUEST_FAILED);
		}
	}
}
