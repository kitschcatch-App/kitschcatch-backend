package com.kitschcatch.backend.domain.payment.service;

import com.kitschcatch.backend.domain.order.entity.OrderStatus;
import com.kitschcatch.backend.domain.order.entity.PgProvider;
import com.kitschcatch.backend.domain.order.entity.PurchaseOrder;
import com.kitschcatch.backend.domain.order.repository.PurchaseOrderRepository;
import com.kitschcatch.backend.domain.payment.client.TossPaymentClient;
import com.kitschcatch.backend.domain.payment.client.TossPaymentException;
import com.kitschcatch.backend.domain.payment.client.TossPaymentProperties;
import com.kitschcatch.backend.domain.payment.client.TossPaymentResult;
import com.kitschcatch.backend.domain.payment.dto.CancelPaymentRequest;
import com.kitschcatch.backend.domain.payment.dto.ConfirmPaymentRequest;
import com.kitschcatch.backend.domain.payment.dto.CreatePaymentRequest;
import com.kitschcatch.backend.domain.payment.dto.PaymentResponse;
import com.kitschcatch.backend.domain.payment.entity.Payment;
import com.kitschcatch.backend.domain.payment.entity.PaymentMethod;
import com.kitschcatch.backend.domain.payment.entity.PaymentStatus;
import com.kitschcatch.backend.domain.payment.repository.PaymentRepository;
import com.kitschcatch.backend.domain.post.entity.ProductStatus;
import com.kitschcatch.backend.global.exception.BusinessException;
import com.kitschcatch.backend.global.exception.ErrorCode;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class PaymentService {

	private static final String TOSS_APPROVED_STATUS = "DONE";
	private static final String TOSS_CANCELED_STATUS = "CANCELED";
	private static final List<PaymentStatus> ACTIVE_PAYMENT_STATUSES = List.of(
		PaymentStatus.READY,
		PaymentStatus.REQUESTED,
		PaymentStatus.CONFIRMING,
		PaymentStatus.APPROVED,
		PaymentStatus.CANCELING
	);

	private final PaymentRepository paymentRepository;
	private final PurchaseOrderRepository orderRepository;
	private final TossPaymentClient tossPaymentClient;
	private final TossPaymentProperties tossPaymentProperties;
	private final Clock clock;
	private final TransactionOperations transactionOperations;

	@Autowired
	public PaymentService(
		PaymentRepository paymentRepository,
		PurchaseOrderRepository orderRepository,
		TossPaymentClient tossPaymentClient,
		TossPaymentProperties tossPaymentProperties,
		PlatformTransactionManager transactionManager
	) {
		this(
			paymentRepository,
			orderRepository,
			tossPaymentClient,
			tossPaymentProperties,
			Clock.systemDefaultZone(),
			new TransactionTemplate(transactionManager)
		);
	}

	PaymentService(
		PaymentRepository paymentRepository,
		PurchaseOrderRepository orderRepository,
		TossPaymentClient tossPaymentClient,
		TossPaymentProperties tossPaymentProperties,
		Clock clock,
		TransactionOperations transactionOperations
	) {
		this.paymentRepository = paymentRepository;
		this.orderRepository = orderRepository;
		this.tossPaymentClient = tossPaymentClient;
		this.tossPaymentProperties = tossPaymentProperties;
		this.clock = clock;
		this.transactionOperations = transactionOperations;
	}

	public PaymentResponse createPayment(Long userId, CreatePaymentRequest request) {
		ensureCheckoutConfigured();
		return transactionOperations.execute(status -> prepareCreatePayment(userId, request));
	}

	public PaymentResponse confirmPayment(Long userId, Long paymentId, ConfirmPaymentRequest request) {
		PreparedConfirmPayment prepared = transactionOperations.execute(
			status -> prepareConfirmPayment(userId, paymentId, request.paymentKey())
		);
		if (prepared.response() != null) {
			return prepared.response();
		}
		if (prepared.errorCode() != null) {
			throw new BusinessException(prepared.errorCode());
		}

		TossPaymentResult result;
		try {
			result = tossPaymentClient.confirmPayment(
				prepared.paymentKey(),
				prepared.pgOrderId(),
				prepared.amount(),
				prepared.idempotencyKey()
			);
		} catch (TossPaymentException exception) {
			PaymentResponse reconciled = reconcileConfirmFailure(userId, paymentId, prepared.paymentKey(), exception);
			if (reconciled != null) {
				return reconciled;
			}
			throw new BusinessException(ErrorCode.PAYMENT_PROVIDER_FAILED, exception.getMessage());
		}

		return transactionOperations.execute(status -> completeConfirmPayment(userId, paymentId, request.paymentKey(), result));
	}

	public PaymentResponse getPayment(Long userId, Long paymentId) {
		return transactionOperations.execute(status -> {
			Payment payment = findPaymentForUpdate(userId, paymentId);
			expirePaymentIfOrderExpired(payment);
			return toResponse(payment);
		});
	}

	public PaymentResponse cancelPayment(Long userId, Long paymentId, CancelPaymentRequest request) {
		PreparedCancelPayment prepared = transactionOperations.execute(status -> prepareCancelPayment(userId, paymentId));
		if (prepared.response() != null) {
			return prepared.response();
		}

		TossPaymentResult result;
		try {
			result = tossPaymentClient.cancelPayment(
				prepared.paymentKey(),
				request.cancelReason(),
				prepared.idempotencyKey()
			);
		} catch (TossPaymentException exception) {
			PaymentResponse reconciled = reconcileCancelFailure(userId, paymentId, prepared.paymentKey(), exception);
			if (reconciled != null) {
				return reconciled;
			}
			throw new BusinessException(ErrorCode.PAYMENT_PROVIDER_FAILED, exception.getMessage());
		}

		return transactionOperations.execute(status -> completeCancelPayment(userId, paymentId, result));
	}

	private PaymentResponse prepareCreatePayment(Long userId, CreatePaymentRequest request) {
		PurchaseOrder order = orderRepository.findByIdAndUserIdForUpdate(request.orderId(), userId)
			.orElseThrow(() -> new BusinessException(ErrorCode.ORDER_NOT_FOUND));
		if (request.method() == PaymentMethod.VIRTUAL_ACCOUNT) {
			throw new BusinessException(ErrorCode.INVALID_INPUT_VALUE);
		}
		Optional<Payment> activePayment = findActivePayment(order);
		ErrorCode errorCode = validatePayableOrder(order);
		if (errorCode != null) {
			activePayment.ifPresent(this::failPendingPayment);
			throw new BusinessException(errorCode);
		}

		if (activePayment.isPresent()) {
			return toReusablePayment(activePayment.get());
		}

		Payment payment = Payment.ready(
			order,
			PgProvider.TOSS_PAYMENTS,
			request.method(),
			order.getAmount()
		);
		payment.request();
		return toResponse(paymentRepository.save(payment));
	}

	private PreparedConfirmPayment prepareConfirmPayment(Long userId, Long paymentId, String paymentKey) {
		Payment payment = findPaymentForUpdate(userId, paymentId);
		if (payment.getStatus() == PaymentStatus.APPROVED) {
			if (Objects.equals(payment.getPgPaymentKey(), paymentKey)) {
				return PreparedConfirmPayment.completed(toResponse(payment));
			}
			throw new BusinessException(ErrorCode.PAYMENT_INVALID_STATE);
		}
		if (payment.getStatus() != PaymentStatus.REQUESTED) {
			if (payment.getStatus() != PaymentStatus.CONFIRMING
				|| !Objects.equals(payment.getPgPaymentKey(), paymentKey)) {
				throw new BusinessException(ErrorCode.PAYMENT_INVALID_STATE);
			}
			return PreparedConfirmPayment.ready(
				paymentKey,
				payment.getPgOrderId(),
				payment.getAmount(),
				"payment-confirm-" + payment.getId()
			);
		}
		ErrorCode errorCode = validatePayableOrder(payment.getOrder());
		if (errorCode != null) {
			payment.fail();
			return PreparedConfirmPayment.failed(errorCode);
		}
		payment.startConfirm(paymentKey);
		return PreparedConfirmPayment.ready(
			paymentKey,
			payment.getPgOrderId(),
			payment.getAmount(),
			"payment-confirm-" + payment.getId()
		);
	}

	private PaymentResponse completeConfirmPayment(
		Long userId,
		Long paymentId,
		String paymentKey,
		TossPaymentResult result
	) {
		Payment payment = findPaymentForUpdate(userId, paymentId);
		if (payment.getStatus() == PaymentStatus.APPROVED && Objects.equals(payment.getPgPaymentKey(), paymentKey)) {
			return toResponse(payment);
		}
		if (payment.getStatus() != PaymentStatus.REQUESTED && payment.getStatus() != PaymentStatus.CONFIRMING) {
			throw new BusinessException(ErrorCode.PAYMENT_INVALID_STATE);
		}
		ErrorCode errorCode = confirmResultError(payment, result);
		if (errorCode != null) {
			payment.fail();
			throw new BusinessException(errorCode);
		}
		payment.approve(result.paymentKey(), result.transactionKey());
		payment.getOrder().markPaid();
		payment.getOrder().getPost().markSoldOut();
		return toResponse(payment);
	}

	private PreparedCancelPayment prepareCancelPayment(Long userId, Long paymentId) {
		Payment payment = findPaymentForUpdate(userId, paymentId);
		if (payment.getStatus() == PaymentStatus.CANCELED) {
			return PreparedCancelPayment.completed(toResponse(payment));
		}
		if (payment.getStatus() != PaymentStatus.APPROVED) {
			if (payment.getStatus() != PaymentStatus.CANCELING) {
				throw new BusinessException(ErrorCode.PAYMENT_INVALID_STATE);
			}
			return PreparedCancelPayment.ready(
				payment.getPgPaymentKey(),
				"payment-cancel-" + payment.getId()
			);
		}
		payment.startCancel();
		return PreparedCancelPayment.ready(
			payment.getPgPaymentKey(),
			"payment-cancel-" + payment.getId()
		);
	}

	private PaymentResponse completeCancelPayment(Long userId, Long paymentId, TossPaymentResult result) {
		Payment payment = findPaymentForUpdate(userId, paymentId);
		if (payment.getStatus() == PaymentStatus.CANCELED) {
			return toResponse(payment);
		}
		if (payment.getStatus() != PaymentStatus.CANCELING) {
			throw new BusinessException(ErrorCode.PAYMENT_INVALID_STATE);
		}
		ErrorCode errorCode = cancelResultError(payment, result);
		if (errorCode != null) {
			payment.restoreApproved();
			throw new BusinessException(errorCode);
		}
		payment.cancel(result.transactionKey());
		payment.getOrder().cancel();
		payment.getOrder().getPost().reopen();
		return toResponse(payment);
	}

	private PaymentResponse reconcileConfirmFailure(
		Long userId,
		Long paymentId,
		String paymentKey,
		TossPaymentException confirmException
	) {
		try {
			TossPaymentResult result = tossPaymentClient.getPayment(paymentKey);
			if (TOSS_APPROVED_STATUS.equals(result.status())) {
				return transactionOperations.execute(
					status -> completeConfirmPayment(userId, paymentId, paymentKey, result)
				);
			}
		} catch (TossPaymentException queryException) {
			if (confirmException.isClientError()) {
				restoreConfirmFailure(userId, paymentId, paymentKey);
			}
			return null;
		}
		if (confirmException.isClientError()) {
			restoreConfirmFailure(userId, paymentId, paymentKey);
		}
		return null;
	}

	private PaymentResponse reconcileCancelFailure(
		Long userId,
		Long paymentId,
		String paymentKey,
		TossPaymentException cancelException
	) {
		try {
			TossPaymentResult result = tossPaymentClient.getPayment(paymentKey);
			if (TOSS_CANCELED_STATUS.equals(result.status())) {
				return transactionOperations.execute(status -> completeCancelPayment(userId, paymentId, result));
			}
		} catch (TossPaymentException queryException) {
			if (cancelException.isClientError()) {
				restoreCancelFailure(userId, paymentId);
			}
			return null;
		}
		if (cancelException.isClientError()) {
			restoreCancelFailure(userId, paymentId);
		}
		return null;
	}

	private void restoreConfirmFailure(Long userId, Long paymentId, String paymentKey) {
		transactionOperations.execute(status -> {
			Payment payment = findPaymentForUpdate(userId, paymentId);
			if (payment.getStatus() == PaymentStatus.CONFIRMING
				&& Objects.equals(payment.getPgPaymentKey(), paymentKey)) {
				payment.restoreRequested();
			}
			return null;
		});
	}

	private void restoreCancelFailure(Long userId, Long paymentId) {
		transactionOperations.execute(status -> {
			Payment payment = findPaymentForUpdate(userId, paymentId);
			if (payment.getStatus() == PaymentStatus.CANCELING) {
				payment.restoreApproved();
			}
			return null;
		});
	}

	private Payment findPaymentForUpdate(Long userId, Long paymentId) {
		return paymentRepository.findByIdAndOrderUserIdForUpdate(paymentId, userId)
			.orElseThrow(() -> new BusinessException(ErrorCode.PAYMENT_NOT_FOUND));
	}

	private Optional<Payment> findActivePayment(PurchaseOrder order) {
		return paymentRepository.findFirstByOrderIdAndStatusInOrderByCreatedAtDesc(
			order.getId(),
			ACTIVE_PAYMENT_STATUSES
		);
	}

	private PaymentResponse toReusablePayment(Payment payment) {
		if (payment.getStatus() == PaymentStatus.READY) {
			payment.request();
		}
		if (payment.getStatus() == PaymentStatus.REQUESTED) {
			return toResponse(payment);
		}
		throw new BusinessException(ErrorCode.PAYMENT_INVALID_STATE);
	}

	private void failPendingPayment(Payment payment) {
		if (payment.getStatus() == PaymentStatus.READY
			|| payment.getStatus() == PaymentStatus.REQUESTED
			|| payment.getStatus() == PaymentStatus.CONFIRMING) {
			payment.fail();
		}
	}

	private void expirePaymentIfOrderExpired(Payment payment) {
		if (!payment.getOrder().isExpired(LocalDateTime.now(clock))) {
			return;
		}
		if (payment.getStatus() != PaymentStatus.READY
			&& payment.getStatus() != PaymentStatus.REQUESTED
			&& payment.getStatus() != PaymentStatus.CONFIRMING) {
			return;
		}
		payment.getOrder().expire();
		payment.getOrder().getPost().reopen();
		payment.fail();
	}

	private ErrorCode validatePayableOrder(PurchaseOrder order) {
		LocalDateTime now = LocalDateTime.now(clock);
		if (order.isExpired(now)) {
			order.expire();
			order.getPost().reopen();
			return ErrorCode.ORDER_EXPIRED;
		}
		if (order.getOrderStatus() != OrderStatus.PENDING || order.getPost().getProductStatus() != ProductStatus.RESERVED) {
			return ErrorCode.PAYMENT_INVALID_STATE;
		}
		return null;
	}

	private ErrorCode confirmResultError(Payment payment, TossPaymentResult result) {
		if (!Objects.equals(payment.getPgOrderId(), result.orderId())) {
			return ErrorCode.PAYMENT_PROVIDER_RESPONSE_INVALID;
		}
		if (!Objects.equals(payment.getAmount(), result.totalAmount())) {
			return ErrorCode.PAYMENT_AMOUNT_MISMATCH;
		}
		if (!TOSS_APPROVED_STATUS.equals(result.status())) {
			return ErrorCode.PAYMENT_INVALID_STATE;
		}
		return null;
	}

	private ErrorCode cancelResultError(Payment payment, TossPaymentResult result) {
		if (!Objects.equals(payment.getPgOrderId(), result.orderId())
			|| !Objects.equals(payment.getAmount(), result.totalAmount())
			|| !TOSS_CANCELED_STATUS.equals(result.status())) {
			return ErrorCode.PAYMENT_PROVIDER_RESPONSE_INVALID;
		}
		return null;
	}

	private void ensureCheckoutConfigured() {
		if (!tossPaymentProperties.hasCheckoutValues()) {
			throw new BusinessException(ErrorCode.PAYMENT_PROVIDER_FAILED, "토스페이먼츠 클라이언트 키와 리다이렉트 URL 설정이 필요합니다.");
		}
	}

	private PaymentResponse toResponse(Payment payment) {
		return new PaymentResponse(
			payment.getId(),
			payment.getOrder().getId(),
			payment.getPgOrderId(),
			payment.getAmount(),
			payment.getMethod(),
			payment.getStatus(),
			payment.getOrder().getPost().getTitle(),
			tossPaymentProperties.clientKey(),
			redirectUrl(tossPaymentProperties.successUrl(), payment),
			redirectUrl(tossPaymentProperties.failUrl(), payment),
			payment.getOrder().getExpiresAt(),
			payment.getCreatedAt(),
			payment.getUpdatedAt()
		);
	}

	private String redirectUrl(String baseUrl, Payment payment) {
		String separator = baseUrl.contains("?") ? "&" : "?";
		return baseUrl + separator + "paymentId=" + payment.getId();
	}

	private record PreparedConfirmPayment(
		PaymentResponse response,
		String paymentKey,
		String pgOrderId,
		Long amount,
		String idempotencyKey,
		ErrorCode errorCode
	) {

		private static PreparedConfirmPayment completed(PaymentResponse response) {
			return new PreparedConfirmPayment(response, null, null, null, null, null);
		}

		private static PreparedConfirmPayment ready(
			String paymentKey,
			String pgOrderId,
			Long amount,
			String idempotencyKey
		) {
			return new PreparedConfirmPayment(null, paymentKey, pgOrderId, amount, idempotencyKey, null);
		}

		private static PreparedConfirmPayment failed(ErrorCode errorCode) {
			return new PreparedConfirmPayment(null, null, null, null, null, errorCode);
		}
	}

	private record PreparedCancelPayment(
		PaymentResponse response,
		String paymentKey,
		String idempotencyKey
	) {

		private static PreparedCancelPayment completed(PaymentResponse response) {
			return new PreparedCancelPayment(response, null, null);
		}

		private static PreparedCancelPayment ready(String paymentKey, String idempotencyKey) {
			return new PreparedCancelPayment(null, paymentKey, idempotencyKey);
		}
	}
}
