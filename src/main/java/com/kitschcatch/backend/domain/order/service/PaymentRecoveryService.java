// PG 조회 결과를 결제·주문·상품 상태에 원자적으로 반영하는 서비스
package com.kitschcatch.backend.domain.order.service;

import com.kitschcatch.backend.domain.order.dto.PaymentResponse;
import com.kitschcatch.backend.domain.order.entity.Payment;
import com.kitschcatch.backend.domain.order.entity.PaymentAttempt;
import com.kitschcatch.backend.domain.order.entity.PaymentAttemptOperation;
import com.kitschcatch.backend.domain.order.entity.PaymentAttemptStatus;
import com.kitschcatch.backend.domain.order.entity.PaymentRecoveryState;
import com.kitschcatch.backend.domain.order.entity.PurchaseOrder;
import com.kitschcatch.backend.domain.order.repository.PaymentAttemptRepository;
import com.kitschcatch.backend.domain.order.repository.PaymentRepository;
import com.kitschcatch.backend.domain.order.toss.TossPaymentResponse;
import com.kitschcatch.backend.global.exception.BusinessException;
import com.kitschcatch.backend.global.exception.ErrorCode;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PaymentRecoveryService {

	private static final String TOSS_DONE = "DONE";
	private static final String TOSS_CANCELED = "CANCELED";
	private static final String TOSS_ABORTED = "ABORTED";
	private static final String TOSS_EXPIRED = "EXPIRED";

	private final PaymentAttemptRepository paymentAttemptRepository;
	private final PaymentRepository paymentRepository;
	private final OrderReservationService reservationService;

	public PaymentRecoveryService(
		PaymentAttemptRepository paymentAttemptRepository,
		PaymentRepository paymentRepository,
		OrderReservationService reservationService
	) {
		this.paymentAttemptRepository = paymentAttemptRepository;
		this.paymentRepository = paymentRepository;
		this.reservationService = reservationService;
	}

	@Transactional
	public PaymentResponse recover(String attemptId, TossPaymentResponse tossResponse) {
		Long orderId = paymentAttemptRepository.findOrderIdByAttemptId(attemptId)
			.orElseThrow(() -> new BusinessException(ErrorCode.ORDER_NOT_FOUND));
		String paymentId = paymentAttemptRepository.findPaymentIdByAttemptId(attemptId)
			.orElseThrow(() -> new BusinessException(ErrorCode.PAYMENT_NOT_FOUND));
		reservationService.lockOrder(orderId);
		Payment payment = paymentRepository.findByPaymentIdForUpdate(paymentId)
			.orElseThrow(() -> new BusinessException(ErrorCode.PAYMENT_NOT_FOUND));
		PaymentAttempt lockedAttempt = paymentAttemptRepository.findByAttemptIdForUpdate(attemptId)
			.orElseThrow(() -> new BusinessException(ErrorCode.PAYMENT_NOT_FOUND));
		if (payment.getCurrentAttemptId() != null && !attemptId.equals(payment.getCurrentAttemptId())) {
			lockedAttempt.markReviewRequired("오래된 결제 시도의 응답입니다.");
			return toResponse(payment);
		}

		if (!samePayment(lockedAttempt, tossResponse)) {
			lockedAttempt.markReviewRequired("PG 응답 식별자 또는 금액이 일치하지 않습니다.");
			payment.markReviewRequired("PG_IDENTIFIER_MISMATCH");
			return toResponse(payment);
		}

		if (lockedAttempt.getAttemptStatus() == PaymentAttemptStatus.SUCCEEDED
			|| lockedAttempt.getAttemptStatus() == PaymentAttemptStatus.FAILED
			|| lockedAttempt.getAttemptStatus() == PaymentAttemptStatus.EXPIRED) {
			return toResponse(payment);
		}

		LocalDateTime verifiedAt = LocalDateTime.now();
		payment.markVerified(verifiedAt);
		if (lockedAttempt.getOperation() == PaymentAttemptOperation.CONFIRM) {
			recoverConfirmation(payment, lockedAttempt, tossResponse);
		} else {
			recoverCancellation(payment, lockedAttempt, tossResponse);
		}
		return toResponse(payment);
	}

	@Transactional
	public void recordLookupFailure(String attemptId, String failureReason) {
		String paymentId = paymentAttemptRepository.findPaymentIdByAttemptId(attemptId)
			.orElseThrow(() -> new BusinessException(ErrorCode.PAYMENT_NOT_FOUND));
		Payment payment = paymentRepository.findByPaymentIdForUpdate(paymentId)
			.orElseThrow(() -> new BusinessException(ErrorCode.PAYMENT_NOT_FOUND));
		PaymentAttempt attempt = paymentAttemptRepository.findByAttemptIdForUpdate(attemptId)
			.orElseThrow(() -> new BusinessException(ErrorCode.PAYMENT_NOT_FOUND));
		if (!attempt.isActive()) {
			return;
		}
		attempt.markUnknown();
		attempt.scheduleNextCheck(LocalDateTime.now().plusSeconds(nextDelaySeconds(attempt.getCheckCount())));
		attempt.markReviewRequired(failureReason);
		payment.markRecoveryPending();
	}

	private long nextDelaySeconds(int checkCount) {
		return Math.min(900L, 30L * (1L << Math.min(checkCount, 5)));
	}

	private void recoverConfirmation(Payment payment, PaymentAttempt attempt, TossPaymentResponse response) {
		if (TOSS_DONE.equals(response.status())) {
			attempt.markSucceeded(response.status(), parseTime(response.approvedAt()), parseTime(response.canceledAt()));
			payment.confirm(response.paymentKey());
			payment.getOrder().getPost().markSold(payment.getOrder().getOrderNumber());
			return;
		}
		if (TOSS_CANCELED.equals(response.status()) && isFullyCanceled(response)) {
			attempt.markSucceeded(response.status(), parseTime(response.approvedAt()), parseTime(response.canceledAt()));
			payment.cancel();
			payment.getOrder().getPost().releaseOrder(payment.getOrder().getOrderNumber());
			return;
		}
		if (TOSS_ABORTED.equals(response.status()) || TOSS_EXPIRED.equals(response.status())) {
			attempt.markFailed(response.status(), "PG 결제 승인이 확정적으로 실패했습니다.");
			payment.markFailed(response.status());
			return;
		}
		markUnknown(payment, attempt, response.status());
	}

	private void recoverCancellation(Payment payment, PaymentAttempt attempt, TossPaymentResponse response) {
		if (TOSS_CANCELED.equals(response.status()) && isFullyCanceled(response)) {
			attempt.markSucceeded(response.status(), parseTime(response.approvedAt()), parseTime(response.canceledAt()));
			payment.cancel();
			payment.getOrder().getPost().releaseOrder(payment.getOrder().getOrderNumber());
			return;
		}
		markUnknown(payment, attempt, response.status());
	}

	private void markUnknown(Payment payment, PaymentAttempt attempt, String pgStatus) {
		attempt.markUnknown();
		payment.markRecoveryPending();
		if (pgStatus != null) {
			attempt.markReviewRequired("PG 상태 확인이 더 필요합니다: " + pgStatus);
		}
	}

	private boolean samePayment(PaymentAttempt attempt, TossPaymentResponse response) {
		if (response == null || !attempt.getPgOrderId().equals(response.orderId())
			|| !attempt.getAmount().equals(response.totalAmount())) {
			return false;
		}
		return attempt.getPaymentKey() == null || response.paymentKey() == null
			|| attempt.getPaymentKey().equals(response.paymentKey());
	}

	private boolean isFullyCanceled(TossPaymentResponse response) {
		return response.balanceAmount() == null || response.balanceAmount() == 0L;
	}

	private LocalDateTime parseTime(String value) {
		if (value == null) {
			return null;
		}
		try {
			return OffsetDateTime.parse(value).toLocalDateTime();
		} catch (DateTimeParseException exception) {
			return null;
		}
	}

	private PaymentResponse toResponse(Payment payment) {
		PurchaseOrder order = payment.getOrder();
		return new PaymentResponse(
			payment.getPaymentId(),
			order.getOrderNumber(),
			payment.getAmount(),
			payment.getPaymentStatus(),
			payment.getCreatedAt(),
			payment.getApprovedAt()
		);
	}
}
