// PG 조회 결과를 결제·주문·상품 상태에 원자적으로 반영하는 서비스
package com.kitschcatch.backend.domain.order.service;

import com.kitschcatch.backend.domain.notification.NotificationEvents;
import com.kitschcatch.backend.domain.notification.NotificationType;
import com.kitschcatch.backend.domain.order.dto.PaymentResponse;
import com.kitschcatch.backend.domain.order.entity.Payment;
import com.kitschcatch.backend.domain.order.entity.PaymentAttempt;
import com.kitschcatch.backend.domain.order.entity.PaymentAttemptOperation;
import com.kitschcatch.backend.domain.order.entity.PaymentAttemptStatus;
import com.kitschcatch.backend.domain.order.entity.PaymentRecoveryState;
import com.kitschcatch.backend.domain.order.entity.OrderStatus;
import com.kitschcatch.backend.domain.order.entity.PaymentStatus;
import com.kitschcatch.backend.domain.order.entity.PurchaseOrder;
import com.kitschcatch.backend.domain.post.entity.ProductStatus;
import com.kitschcatch.backend.domain.order.repository.PaymentAttemptRepository;
import com.kitschcatch.backend.domain.order.repository.PaymentRepository;
import com.kitschcatch.backend.domain.order.toss.TossPaymentResponse;
import com.kitschcatch.backend.global.exception.BusinessException;
import com.kitschcatch.backend.global.exception.ErrorCode;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PaymentRecoveryService {

	@org.springframework.beans.factory.annotation.Autowired
	private ApplicationEventPublisher notificationPublisher;

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
		return recover(attemptId, tossResponse, null);
	}

	@Transactional
	public PaymentResponse recover(String attemptId, TossPaymentResponse tossResponse, String leaseToken) {
		Long orderId = paymentAttemptRepository.findOrderIdByAttemptId(attemptId)
			.orElseThrow(() -> new BusinessException(ErrorCode.ORDER_NOT_FOUND));
		String paymentId = paymentAttemptRepository.findPaymentIdByAttemptId(attemptId)
			.orElseThrow(() -> new BusinessException(ErrorCode.PAYMENT_NOT_FOUND));
		reservationService.lockOrder(orderId);
		Payment payment = paymentRepository.findByPaymentIdForUpdate(paymentId)
			.orElseThrow(() -> new BusinessException(ErrorCode.PAYMENT_NOT_FOUND));
		PaymentAttempt lockedAttempt = paymentAttemptRepository.findByAttemptIdForUpdate(attemptId)
			.orElseThrow(() -> new BusinessException(ErrorCode.PAYMENT_NOT_FOUND));
		if (!validLease(lockedAttempt, leaseToken)) {
			return toResponse(payment);
		}
		if (!samePayment(lockedAttempt, tossResponse)) {
			lockedAttempt.recordReviewReason("PG 응답 식별자 또는 금액이 일치하지 않습니다.");
			lockedAttempt.scheduleNextCheck(LocalDateTime.now().plusMinutes(15));
			payment.markReviewRequired("PG_IDENTIFIER_MISMATCH");
			return toResponse(payment);
		}
		if (payment.getCurrentAttemptId() != null && !attemptId.equals(payment.getCurrentAttemptId())) {
			if (!TOSS_ABORTED.equals(tossResponse.status()) && !TOSS_EXPIRED.equals(tossResponse.status())) {
				lockedAttempt.recordReviewReason("오래된 결제 시도의 응답입니다.");
				payment.markReviewRequired("STALE_ATTEMPT_RESULT");
			}
			return toResponse(payment);
		}

		if (!samePayment(lockedAttempt, tossResponse)) {
			lockedAttempt.markReviewRequired("PG 응답 식별자 또는 금액이 일치하지 않습니다.");
			payment.markReviewRequired("PG_IDENTIFIER_MISMATCH");
			return toResponse(payment);
		}

        if (payment.getOrder().getOrderStatus() == OrderStatus.PURCHASE_CONFIRMED) {
            if (!TOSS_DONE.equals(tossResponse.status()) || payment.getPaymentStatus() != PaymentStatus.SUCCESS) {
                markReview(payment, lockedAttempt, "구매 확정 이후 PG 상태가 변경되어 운영 확인이 필요합니다.");
            } else {
                payment.markVerified(LocalDateTime.now());
                lockedAttempt.releaseLease();
            }
            // 확인 필요 표시를 자동 해제하거나 확정된 주문을 PAID로 되돌리지 않는다.
            return toResponse(payment);
        }

		if (lockedAttempt.getAttemptStatus() == PaymentAttemptStatus.SUCCEEDED
			&& lockedAttempt.getOperation() == PaymentAttemptOperation.CONFIRM
			&& payment.getPaymentStatus() == PaymentStatus.SUCCESS) {
			payment.markVerified(LocalDateTime.now());
			if (TOSS_DONE.equals(tossResponse.status())) {
				lockedAttempt.releaseLease();
				return toResponse(payment);
			}
			if (TOSS_CANCELED.equals(tossResponse.status())) {
				if (isFullyCanceled(tossResponse)) {
					payment.cancel();
					releaseUnshippedPost(payment);
					if (notificationPublisher != null) {
					    notificationPublisher.publishEvent(
					        new NotificationEvents.PaymentChanged(payment, NotificationType.PAYMENT_CANCELED));
					}
				} else {
					markReview(payment, lockedAttempt, "부분 취소 또는 잔액이 남은 PG 결과입니다.");
				}
			} else {
				markReview(payment, lockedAttempt, "성공 결제와 모순되는 PG 상태입니다: " + tossResponse.status());
			}
			lockedAttempt.releaseLease();
			return toResponse(payment);
		}

		if (lockedAttempt.getAttemptStatus() == PaymentAttemptStatus.SUCCEEDED
			|| lockedAttempt.getAttemptStatus() == PaymentAttemptStatus.FAILED
			|| lockedAttempt.getAttemptStatus() == PaymentAttemptStatus.EXPIRED) {
			if (lockedAttempt.getAttemptStatus() != PaymentAttemptStatus.SUCCEEDED
				&& (TOSS_DONE.equals(tossResponse.status()) || TOSS_CANCELED.equals(tossResponse.status()))) {
				lockedAttempt.recordReviewReason("종료된 승인 시도와 모순되는 PG 결과입니다.");
				payment.markReviewRequired("TERMINAL_ATTEMPT_CONTRADICTION");
			}
			lockedAttempt.releaseLease();
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
		recordLookupFailure(attemptId, failureReason, null);
	}

	@Transactional
	public void recordLookupFailure(String attemptId, String failureReason, String leaseToken) {
		String paymentId = paymentAttemptRepository.findPaymentIdByAttemptId(attemptId)
			.orElseThrow(() -> new BusinessException(ErrorCode.PAYMENT_NOT_FOUND));
		Payment payment = paymentRepository.findByPaymentIdForUpdate(paymentId)
			.orElseThrow(() -> new BusinessException(ErrorCode.PAYMENT_NOT_FOUND));
		PaymentAttempt attempt = paymentAttemptRepository.findByAttemptIdForUpdate(attemptId)
			.orElseThrow(() -> new BusinessException(ErrorCode.PAYMENT_NOT_FOUND));
		if (!validLease(attempt, leaseToken)) {
			return;
		}
		if (!attempt.isActive()) {
			attempt.scheduleNextCheck(LocalDateTime.now().plusMinutes(15));
			return;
		}
		attempt.markUnknown();
		attempt.scheduleNextCheck(LocalDateTime.now().plusSeconds(nextDelaySeconds(attempt.getCheckCount())));
		attempt.recordReviewReason(failureReason);
		if (!payment.isRecoveryReviewRequired()) payment.markRecoveryPending();
	}

	@Transactional
	public void recordConfirmedRejection(String attemptId, String pgCode) {
		Long orderId = paymentAttemptRepository.findOrderIdByAttemptId(attemptId)
			.orElseThrow(() -> new BusinessException(ErrorCode.ORDER_NOT_FOUND));
		String paymentId = paymentAttemptRepository.findPaymentIdByAttemptId(attemptId)
			.orElseThrow(() -> new BusinessException(ErrorCode.PAYMENT_NOT_FOUND));
		reservationService.lockOrder(orderId);
		Payment payment = paymentRepository.findByPaymentIdForUpdate(paymentId).orElseThrow();
		PaymentAttempt attempt = paymentAttemptRepository.findByAttemptIdForUpdate(attemptId).orElseThrow();
		if (!attemptId.equals(payment.getCurrentAttemptId()) || !attempt.isActive()
			|| attempt.getOperation() != PaymentAttemptOperation.CONFIRM
			|| payment.isRecoveryReviewRequired()) return;
		if (!"REJECT_CARD_PAYMENT".equals(pgCode) && !"REJECT_CARD_COMPANY".equals(pgCode)) return;
		attempt.markFailed(pgCode, "PG 승인 요청의 확정 거절 코드입니다.");
		payment.markFailed(pgCode);
		// 기한을 지난 확정 실패는 기존 만료 규칙으로 함께 종료한다.
		reservationService.expireOrder(orderId);
	}

	private boolean validLease(PaymentAttempt attempt, String token) {
		return token == null || (token.equals(attempt.getLeaseToken())
			&& attempt.getLeaseUntil() != null && attempt.getLeaseUntil().isAfter(LocalDateTime.now()));
	}

	@Transactional
	public boolean claim(String attemptId, String leaseToken, LocalDateTime leaseUntil) {
		PaymentAttempt attempt = paymentAttemptRepository.findByAttemptIdForUpdate(attemptId)
			.orElseThrow(() -> new BusinessException(ErrorCode.PAYMENT_NOT_FOUND));
		LocalDateTime now = LocalDateTime.now();
		if (attempt.getAttemptStatus() == PaymentAttemptStatus.PREPARED || !leaseUntil.isAfter(now)) return false;
		boolean terminalApproval = attempt.getOperation() == PaymentAttemptOperation.CONFIRM;
		if ((!attempt.isActive() && !terminalApproval)
			|| attempt.getNextCheckAt().isAfter(now)
			|| (attempt.getLeaseUntil() != null && attempt.getLeaseUntil().isAfter(now))) {
			return false;
		}
		attempt.claim(leaseToken, leaseUntil);
		return true;
	}

	private long nextDelaySeconds(int checkCount) {
		return Math.min(900L, 30L * (1L << Math.min(checkCount, 5)));
	}

	private void recoverConfirmation(Payment payment, PaymentAttempt attempt, TossPaymentResponse response) {
		if (TOSS_DONE.equals(response.status())) {
			if (payment.getOrder().getOrderStatus() != OrderStatus.PENDING
				|| !payment.getOrder().getPost().isOwnedByOrder(payment.getOrder().getOrderNumber())
				|| payment.getOrder().getPost().getProductStatus() != ProductStatus.RESERVED) {
				markReview(payment, attempt, "승인 결과와 주문의 예약 소유권이 일치하지 않습니다.");
				return;
			}
			attempt.markSucceeded(response.status(), parseTime(response.approvedAt()), parseTime(response.canceledAt()));
			payment.confirm(response.paymentKey());
			if (notificationPublisher != null) {
			    notificationPublisher.publishEvent(
			        new NotificationEvents.PaymentChanged(payment, NotificationType.PAYMENT_SUCCESS));
			}
			payment.getOrder().getPost().markSold(payment.getOrder().getOrderNumber());
			return;
		}
		if (TOSS_CANCELED.equals(response.status()) && isFullyCanceled(response)) {
			attempt.markSucceeded(response.status(), parseTime(response.approvedAt()), parseTime(response.canceledAt()));
			payment.cancel();
			releaseUnshippedPost(payment);
			if (notificationPublisher != null) {
			    notificationPublisher.publishEvent(
			        new NotificationEvents.PaymentChanged(payment, NotificationType.PAYMENT_CANCELED));
			}
			return;
		}
		if (TOSS_CANCELED.equals(response.status())) {
			markReview(payment, attempt, "부분 취소 또는 잔액이 남은 PG 결과입니다.");
			return;
		}
		if (TOSS_ABORTED.equals(response.status()) || TOSS_EXPIRED.equals(response.status())) {
			attempt.markFailed(response.status(), "PG 결제 승인이 확정적으로 실패했습니다.");
			payment.markFailed(response.status());
			reservationService.expireOrder(payment.getOrder().getId());
			return;
		}
		markUnknown(payment, attempt, response.status());
	}

	private void recoverCancellation(Payment payment, PaymentAttempt attempt, TossPaymentResponse response) {
		if (TOSS_CANCELED.equals(response.status()) && isFullyCanceled(response)) {
			attempt.markSucceeded(response.status(), parseTime(response.approvedAt()), parseTime(response.canceledAt()));
			payment.cancel();
			releaseUnshippedPost(payment);
			if (notificationPublisher != null) {
			    notificationPublisher.publishEvent(
			        new NotificationEvents.PaymentChanged(payment, NotificationType.PAYMENT_CANCELED));
			}
			return;
		}
		if (TOSS_CANCELED.equals(response.status())) {
			markReview(payment, attempt, "부분 취소 또는 잔액이 남은 PG 결과입니다.");
			return;
		}
		markUnknown(payment, attempt, response.status());
	}

    private void releaseUnshippedPost(Payment payment) {
        if (payment.getOrder().getShipment() == null)
            payment.getOrder().getPost().releaseOrder(payment.getOrder().getOrderNumber());
    }

	private void markUnknown(Payment payment, PaymentAttempt attempt, String pgStatus) {
		attempt.markUnknown();
		if (isTransientStatus(pgStatus)) {
			attempt.scheduleNextCheck(LocalDateTime.now().plusSeconds(nextDelaySeconds(attempt.getCheckCount())));
			if (!payment.isRecoveryReviewRequired()) payment.markRecoveryPending();
			return;
		}
		markReview(payment, attempt, "지원하지 않는 PG 상태입니다: " + pgStatus);
	}

	private void markReview(Payment payment, PaymentAttempt attempt, String reason) {
		attempt.recordReviewReason(reason);
		attempt.scheduleNextCheck(LocalDateTime.now().plusMinutes(15));
		payment.markReviewRequired("PG_STATUS_REVIEW");
	}

	private boolean isTransientStatus(String pgStatus) {
		return pgStatus == null || "READY".equals(pgStatus) || "IN_PROGRESS".equals(pgStatus)
			|| "WAITING_FOR_DEPOSIT".equals(pgStatus) || TOSS_DONE.equals(pgStatus);
	}

	private boolean samePayment(PaymentAttempt attempt, TossPaymentResponse response) {
		if (response == null || !attempt.getPgOrderId().equals(response.orderId())
			|| !attempt.getAmount().equals(response.totalAmount())) {
			return false;
		}
		return response.paymentKey() != null && (attempt.getPaymentKey() == null
			|| attempt.getPaymentKey().equals(response.paymentKey()));
	}

	private boolean isFullyCanceled(TossPaymentResponse response) {
		return response.balanceAmount() != null && response.balanceAmount() == 0L;
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
			payment.getApprovedAt(),
			payment.getCurrentAttemptId(),
			payment.getProcessingOperation(),
			payment.getRecoveryState(),
			isRetryAllowed(payment),
			order.getReservationExpiresAt(),
			payment.getLastVerifiedAt(),
			payment.getLastFailureCode()
		);
	}

	private boolean isRetryAllowed(Payment payment) {
		return payment.getPaymentStatus() == PaymentStatus.FAILED
			&& payment.getRecoveryState() == PaymentRecoveryState.NONE
			&& payment.getOrder().getReservationExpiresAt() != null
			&& payment.getOrder().getOrderStatus() == OrderStatus.PENDING
			&& payment.getOrder().getPost().isOwnedByOrder(payment.getOrder().getOrderNumber())
			&& payment.getOrder().getPost().getProductStatus() == ProductStatus.RESERVED
			&& payment.getOrder().getPost().getDeletedAt() == null
			&& !payment.getOrder().isReservationExpired(LocalDateTime.now());
	}
}
