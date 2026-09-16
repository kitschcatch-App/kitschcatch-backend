// 결제 상태 변경을 짧은 데이터베이스 트랜잭션으로 처리하는 서비스
package com.kitschcatch.backend.domain.order.service;

import com.kitschcatch.backend.domain.order.dto.ConfirmPaymentRequest;
import com.kitschcatch.backend.domain.order.dto.CreatePaymentRequest;
import com.kitschcatch.backend.domain.order.dto.CreatePaymentResponse;
import com.kitschcatch.backend.domain.order.dto.PaymentResponse;
import com.kitschcatch.backend.domain.order.dto.RetryPaymentRequest;
import com.kitschcatch.backend.domain.order.dto.RetryPaymentResponse;
import com.kitschcatch.backend.domain.order.entity.OrderStatus;
import com.kitschcatch.backend.domain.order.entity.Payment;
import com.kitschcatch.backend.domain.order.entity.PaymentAttempt;
import com.kitschcatch.backend.domain.order.entity.PaymentAttemptOperation;
import com.kitschcatch.backend.domain.order.entity.PaymentAttemptStatus;
import com.kitschcatch.backend.domain.order.entity.PaymentOperation;
import com.kitschcatch.backend.domain.order.entity.PaymentStatus;
import com.kitschcatch.backend.domain.order.entity.PurchaseOrder;
import com.kitschcatch.backend.domain.order.repository.PaymentRepository;
import com.kitschcatch.backend.domain.order.repository.PaymentAttemptRepository;
import com.kitschcatch.backend.domain.order.repository.PurchaseOrderRepository;
import com.kitschcatch.backend.domain.post.entity.ProductStatus;
import com.kitschcatch.backend.global.exception.BusinessException;
import com.kitschcatch.backend.global.exception.ErrorCode;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PaymentTransactionService {

	private final PaymentRepository paymentRepository;
	private final PurchaseOrderRepository orderRepository;
	private final OrderReservationService reservationService;
	private final PaymentAttemptRepository paymentAttemptRepository;

	public PaymentTransactionService(
		PaymentRepository paymentRepository,
		PurchaseOrderRepository orderRepository,
		OrderReservationService reservationService
	) {
		this(paymentRepository, orderRepository, reservationService, null);
	}

	@Autowired
	public PaymentTransactionService(
		PaymentRepository paymentRepository,
		PurchaseOrderRepository orderRepository,
		OrderReservationService reservationService,
		PaymentAttemptRepository paymentAttemptRepository
	) {
		this.paymentRepository = paymentRepository;
		this.orderRepository = orderRepository;
		this.reservationService = reservationService;
		this.paymentAttemptRepository = paymentAttemptRepository;
	}

	@Transactional
	public CreatePaymentResponse createPayment(Long userId, CreatePaymentRequest request) {
		Long orderId = orderRepository.findIdByOrderNumberAndUserId(request.orderId(), userId)
			.orElseThrow(() -> new BusinessException(ErrorCode.ORDER_NOT_FOUND));
		PurchaseOrder order = reservationService.lockOrder(orderId);
		validateAmount(request.amount(), order.getAmount());
		validateOrderStatus(order, OrderStatus.PENDING);
		validateReservation(order, ProductStatus.RESERVED);
		validateNotExpired(order);

		Payment existing = paymentRepository.findByOrderIdForUpdate(orderId).orElse(null);
		if (existing != null) {
			if (existing.getPaymentStatus() != PaymentStatus.READY || existing.getPaymentMethod() != request.paymentMethod()) {
				throw new BusinessException(ErrorCode.PAYMENT_INVALID_STATUS);
			}
			PaymentAttempt attempt = ensurePreparedAttempt(existing);
			return toCreatePaymentResponse(existing, attempt);
		}
		Payment payment = paymentRepository.save(Payment.builder()
			.paymentId(generatePaymentId())
			.order(order)
			.amount(request.amount())
			.paymentMethod(request.paymentMethod())
			.paymentStatus(PaymentStatus.READY)
			.build());
		PaymentAttempt attempt = ensurePreparedAttempt(payment);
		return toCreatePaymentResponse(payment, attempt);
	}

	@Transactional(readOnly = true)
	public PaymentResponse getPayment(Long userId, String paymentId) {
		return toResponse(paymentRepository.findByPaymentIdAndOrderUserId(paymentId, userId)
			.orElseThrow(() -> new BusinessException(ErrorCode.PAYMENT_NOT_FOUND)));
	}

	@Transactional
	public PaymentOperationContext startConfirm(Long userId, String paymentId, ConfirmPaymentRequest request) {
		if (!paymentId.equals(request.paymentId())) {
			throw new BusinessException(ErrorCode.PAYMENT_NOT_FOUND);
		}
		Payment payment = findPaymentWithLock(paymentId, userId);
		if (payment.isRecoveryReviewRequired()) {
			throw new BusinessException(ErrorCode.PAYMENT_RETRY_NOT_ALLOWED);
		}
		validatePaymentStatus(payment, PaymentStatus.READY);
		validateOrderStatus(payment.getOrder(), OrderStatus.PENDING);
		validateReservation(payment.getOrder(), ProductStatus.RESERVED);
		validateNotExpired(payment.getOrder());

		if ((request.attemptId() == null || request.attemptId().isBlank()) && paymentAttemptRepository != null) {
			PaymentAttempt prepared = paymentAttemptRepository
				.findFirstByPaymentIdAndOperationAndAttemptStatusOrderBySequenceNumberDesc(
					payment.getId(), PaymentAttemptOperation.CONFIRM, PaymentAttemptStatus.PREPARED)
				.orElse(null);
			if (prepared != null) {
				payment.startConfirmation(request.paymentKey());
				prepared.startConfirmation(request.paymentKey());
				payment.bindAttempt(prepared.getAttemptId(), PaymentOperation.CONFIRM);
				return new PaymentOperationContext(payment.getOrder().getOrderNumber(), prepared.getPgOrderId(),
					payment.getAmount(), request.paymentKey(), prepared.getAttemptId(), payment.getStateVersion(),
					prepared.getPgIdempotencyKey());
			}
		}

		if (request.attemptId() != null && !request.attemptId().isBlank()) {
			if (paymentAttemptRepository == null) {
				throw new BusinessException(ErrorCode.PAYMENT_RETRY_NOT_ALLOWED);
			}
			PaymentAttempt attempt = paymentAttemptRepository.findByAttemptIdForUpdate(request.attemptId())
				.orElseThrow(() -> new BusinessException(ErrorCode.PAYMENT_RETRY_NOT_ALLOWED));
			if (!paymentId.equals(attempt.getPayment().getPaymentId())
				|| attempt.getOperation() != PaymentAttemptOperation.CONFIRM
				|| attempt.getAttemptStatus() != PaymentAttemptStatus.PREPARED) {
				throw new BusinessException(ErrorCode.PAYMENT_RETRY_NOT_ALLOWED);
			}
			payment.startConfirmation(request.paymentKey());
			attempt.startConfirmation(request.paymentKey());
			payment.bindAttempt(attempt.getAttemptId(), PaymentOperation.CONFIRM);
			return new PaymentOperationContext(
				payment.getOrder().getOrderNumber(), attempt.getPgOrderId(), payment.getAmount(),
				request.paymentKey(), attempt.getAttemptId(), payment.getStateVersion(), attempt.getPgIdempotencyKey()
			);
		}

		payment.startConfirmation(request.paymentKey());
		return startAttempt(payment, PaymentAttemptOperation.CONFIRM, request.paymentKey(), null);
	}

	@Transactional
	public RetryPaymentResponse prepareRetry(Long userId, String paymentId, RetryPaymentRequest request) {
		if (paymentAttemptRepository == null) {
			throw new BusinessException(ErrorCode.PAYMENT_RETRY_NOT_ALLOWED);
		}
		Payment payment = findPaymentWithLock(paymentId, userId);
		PaymentAttempt failedAttempt = paymentAttemptRepository.findByAttemptIdForUpdate(request.failedAttemptId())
			.orElseThrow(() -> new BusinessException(ErrorCode.PAYMENT_RETRY_NOT_ALLOWED));
		if (!paymentId.equals(failedAttempt.getPayment().getPaymentId())
			|| failedAttempt.getOperation() != PaymentAttemptOperation.CONFIRM
			|| failedAttempt.getAttemptStatus() != PaymentAttemptStatus.FAILED
			|| payment.getPaymentStatus() != PaymentStatus.FAILED
			|| payment.getOrder().getOrderStatus() != OrderStatus.PENDING
			|| !payment.getOrder().getPost().isOwnedByOrder(payment.getOrder().getOrderNumber())
			|| payment.getOrder().getPost().getProductStatus() != ProductStatus.RESERVED
			|| payment.getOrder().getPost().getDeletedAt() != null
			|| payment.isRecoveryReviewRequired()
			|| payment.getOrder().isReservationExpired(LocalDateTime.now())) {
			throw new BusinessException(ErrorCode.PAYMENT_RETRY_NOT_ALLOWED);
		}

		PaymentAttempt existing = paymentAttemptRepository
			.findByPaymentIdAndSourceAttemptAttemptId(payment.getId(), failedAttempt.getAttemptId())
			.orElse(null);
		if (existing != null) {
			return toRetryResponse(payment, existing);
		}

		int sequence = paymentAttemptRepository.findTopByPaymentIdOrderBySequenceNumberDesc(payment.getId())
			.map(attempt -> attempt.getSequenceNumber() + 1)
			.orElse(1);
		LocalDateTime now = LocalDateTime.now();
		String attemptId = generateId("ATT");
		PaymentAttempt retryAttempt = paymentAttemptRepository.save(PaymentAttempt.builder()
			.attemptId(attemptId)
			.payment(payment)
			.sequenceNumber(sequence)
			.operation(PaymentAttemptOperation.CONFIRM)
			.attemptStatus(PaymentAttemptStatus.PREPARED)
			.sourceAttempt(failedAttempt)
			.pgOrderId(generateId("PG"))
			.amount(payment.getAmount())
			.pgIdempotencyKey("confirm-" + attemptId)
			.requestedAt(now)
			.nextCheckAt(now)
			.build());
		payment.prepareRetry(retryAttempt.getAttemptId());
		return toRetryResponse(payment, retryAttempt);
	}

	@Transactional
	public PaymentResponse completeConfirm(Long userId, String paymentId, String paymentKey) {
		Payment payment = findPaymentWithLock(paymentId, userId);
		validatePaymentStatus(payment, PaymentStatus.PROCESSING);
		validateOrderStatus(payment.getOrder(), OrderStatus.PENDING);
		validateReservation(payment.getOrder(), ProductStatus.RESERVED);
		if (!paymentKey.equals(payment.getPaymentKey())) {
			throw new BusinessException(ErrorCode.PAYMENT_INVALID_STATUS);
		}
		payment.confirm(paymentKey);
		payment.getOrder().getPost().markSold(payment.getOrder().getOrderNumber());
		return toResponse(payment);
	}

	@Transactional
	public PaymentOperationContext startCancel(Long userId, String paymentId) {
		Payment payment = findPaymentWithLock(paymentId, userId);
		validatePaymentStatus(payment, PaymentStatus.SUCCESS);
		validateOrderStatus(payment.getOrder(), OrderStatus.PAID);
		validateReservation(payment.getOrder(), ProductStatus.SOLD_OUT);
		if (payment.getPaymentKey() == null) {
			throw new BusinessException(ErrorCode.PAYMENT_INVALID_STATUS);
		}
		payment.startProcessing(PaymentOperation.CANCEL);
		String pgOrderId = payment.getOrder().getOrderNumber();
		if (paymentAttemptRepository != null) {
			pgOrderId = paymentAttemptRepository
				.findFirstByPaymentIdAndOperationAndAttemptStatusOrderBySequenceNumberDesc(
					payment.getId(), PaymentAttemptOperation.CONFIRM, PaymentAttemptStatus.SUCCEEDED)
				.map(PaymentAttempt::getPgOrderId)
				.orElse(pgOrderId);
		}
		return startAttempt(payment, PaymentAttemptOperation.CANCEL, payment.getPaymentKey(), "고객 요청", pgOrderId);
	}

	@Transactional
	public PaymentResponse completeCancel(Long userId, String paymentId) {
		Payment payment = findPaymentWithLock(paymentId, userId);
		validatePaymentStatus(payment, PaymentStatus.PROCESSING);
		validateOrderStatus(payment.getOrder(), OrderStatus.PAID);
		validateReservation(payment.getOrder(), ProductStatus.SOLD_OUT);
		payment.cancel();
		payment.getOrder().getPost().releaseOrder(payment.getOrder().getOrderNumber());
		return toResponse(payment);
	}

	private Payment findPaymentWithLock(String paymentId, Long userId) {
		// 연관 엔티티를 미리 로드하지 않고 상품 → 주문 → 결제 순서로 잠근다.
		Long orderId = paymentRepository.findOrderIdByPaymentIdAndOrderUserId(paymentId, userId)
			.orElseThrow(() -> new BusinessException(ErrorCode.PAYMENT_NOT_FOUND));
		reservationService.lockOrder(orderId);
		return paymentRepository.findByPaymentIdAndOrderUserIdWithLock(paymentId, userId)
			.orElseThrow(() -> new BusinessException(ErrorCode.PAYMENT_NOT_FOUND));
	}

	private void validateAmount(Long requestedAmount, Long orderAmount) {
		if (!orderAmount.equals(requestedAmount)) {
			throw new BusinessException(ErrorCode.PAYMENT_AMOUNT_MISMATCH);
		}
	}

	private void validateOrderStatus(PurchaseOrder order, OrderStatus expected) {
		if (order.getOrderStatus() != expected) {
			throw new BusinessException(ErrorCode.PAYMENT_INVALID_STATUS);
		}
	}

	private void validatePaymentStatus(Payment payment, PaymentStatus expected) {
		if (payment.getPaymentStatus() != expected) {
			throw new BusinessException(ErrorCode.PAYMENT_INVALID_STATUS);
		}
	}

	private void validateReservation(PurchaseOrder order, ProductStatus expected) {
		if (!order.getPost().isOwnedByOrder(order.getOrderNumber())
			|| order.getPost().getProductStatus() != expected || order.getPost().getDeletedAt() != null) {
			throw new BusinessException(ErrorCode.ORDER_RESERVATION_INVALID);
		}
	}

	private void validateNotExpired(PurchaseOrder order) {
		if (order.isReservationExpired(LocalDateTime.now())) {
			throw new BusinessException(ErrorCode.ORDER_RESERVATION_EXPIRED);
		}
	}

	private PaymentOperationContext startAttempt(
		Payment payment,
		PaymentAttemptOperation operation,
		String paymentKey,
		String cancelReason
	) {
		return startAttempt(payment, operation, paymentKey, cancelReason, payment.getOrder().getOrderNumber());
	}

	private PaymentOperationContext startAttempt(
		Payment payment,
		PaymentAttemptOperation operation,
		String paymentKey,
		String cancelReason,
		String pgOrderId
	) {
		if (paymentAttemptRepository == null) {
			return new PaymentOperationContext(payment.getOrder().getOrderNumber(), payment.getAmount(), paymentKey);
		}
		int sequence = paymentAttemptRepository.findTopByPaymentIdOrderBySequenceNumberDesc(payment.getId())
			.map(attempt -> attempt.getSequenceNumber() + 1)
			.orElse(1);
		LocalDateTime now = LocalDateTime.now();
		String attemptId = generateId("ATT");
		PaymentAttempt attempt = paymentAttemptRepository.save(PaymentAttempt.builder()
			.attemptId(attemptId)
			.payment(payment)
			.sequenceNumber(sequence)
			.operation(operation)
			.attemptStatus(PaymentAttemptStatus.PROCESSING)
			.pgOrderId(pgOrderId)
			.paymentKey(paymentKey)
			.amount(payment.getAmount())
			.cancelReason(cancelReason)
			.pgIdempotencyKey(operation.name().toLowerCase(Locale.ROOT) + "-" + paymentKey)
			.requestedAt(now)
			.nextCheckAt(now)
			.build());
		payment.bindAttempt(attempt.getAttemptId(), operation == PaymentAttemptOperation.CONFIRM
			? PaymentOperation.CONFIRM : PaymentOperation.CANCEL);
		return new PaymentOperationContext(
			payment.getOrder().getOrderNumber(), pgOrderId, payment.getAmount(), paymentKey,
			attempt.getAttemptId(), payment.getStateVersion(), attempt.getPgIdempotencyKey()
		);
	}

	private RetryPaymentResponse toRetryResponse(Payment payment, PaymentAttempt attempt) {
		return new RetryPaymentResponse(
			payment.getPaymentId(), payment.getOrder().getOrderNumber(), attempt.getAttemptId(), attempt.getPgOrderId(),
			payment.getAmount(), payment.getOrder().getReservationExpiresAt(), payment.getPaymentStatus(),
			attempt.getAttemptStatus(), attempt.getAttemptStatus() == PaymentAttemptStatus.PREPARED
				? "OPEN_PAYMENT_WINDOW" : "NONE"
		);
	}

	private PaymentAttempt ensurePreparedAttempt(Payment payment) {
		if (paymentAttemptRepository == null) {
			return null;
		}
		PaymentAttempt prepared = paymentAttemptRepository
			.findFirstByPaymentIdAndOperationAndAttemptStatusOrderBySequenceNumberDesc(
				payment.getId(), PaymentAttemptOperation.CONFIRM, PaymentAttemptStatus.PREPARED)
			.orElse(null);
		if (prepared != null) {
			return prepared;
		}
		int sequence = paymentAttemptRepository.findTopByPaymentIdOrderBySequenceNumberDesc(payment.getId())
			.map(attempt -> attempt.getSequenceNumber() + 1).orElse(1);
		String attemptId = generateId("ATT");
		LocalDateTime now = LocalDateTime.now();
		PaymentAttempt attempt = paymentAttemptRepository.save(PaymentAttempt.builder()
			.attemptId(attemptId).payment(payment).sequenceNumber(sequence)
			.operation(PaymentAttemptOperation.CONFIRM).attemptStatus(PaymentAttemptStatus.PREPARED)
			.pgOrderId(payment.getOrder().getOrderNumber()).amount(payment.getAmount())
			.pgIdempotencyKey("confirm-" + attemptId).requestedAt(now).nextCheckAt(now).build());
		payment.prepareInitialAttempt(attempt.getAttemptId());
		return attempt;
	}

	private CreatePaymentResponse toCreatePaymentResponse(Payment payment, PaymentAttempt attempt) {
		return new CreatePaymentResponse(payment.getPaymentId(), payment.getPaymentStatus(),
			attempt == null ? null : attempt.getAttemptId(), attempt == null ? null : attempt.getPgOrderId(),
			payment.getOrder().getReservationExpiresAt());
	}

	private String generateId(String prefix) {
		String suffix = UUID.randomUUID().toString()
			.replace("-", "")
			.toUpperCase(Locale.ROOT);
		return prefix + "-" + suffix;
	}

	private PaymentResponse toResponse(Payment payment) {
		return new PaymentResponse(
			payment.getPaymentId(),
			payment.getOrder().getOrderNumber(),
			payment.getAmount(),
			payment.getPaymentStatus(),
			payment.getCreatedAt(),
			payment.getApprovedAt(),
			payment.getCurrentAttemptId(),
			payment.getProcessingOperation(),
			payment.getRecoveryState(),
			isRetryAllowed(payment),
			payment.getOrder().getReservationExpiresAt(),
			payment.getLastVerifiedAt(),
			payment.getLastFailureCode()
		);
	}

	private boolean isRetryAllowed(Payment payment) {
		return payment.getPaymentStatus() == PaymentStatus.FAILED
			&& !payment.isRecoveryReviewRequired()
			&& payment.getOrder().getOrderStatus() == OrderStatus.PENDING
			&& payment.getOrder().getPost().isOwnedByOrder(payment.getOrder().getOrderNumber())
			&& payment.getOrder().getPost().getProductStatus() == ProductStatus.RESERVED
			&& payment.getOrder().getPost().getDeletedAt() == null
			&& !payment.getOrder().isReservationExpired(LocalDateTime.now());
	}

	private String generatePaymentId() {
		String suffix = UUID.randomUUID().toString()
			.replace("-", "")
			.toUpperCase(Locale.ROOT);
		return "PAY-" + suffix;
	}
}
