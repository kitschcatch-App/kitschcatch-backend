// 결제 상태 변경을 짧은 데이터베이스 트랜잭션으로 처리하는 서비스
package com.kitschcatch.backend.domain.order.service;

import com.kitschcatch.backend.domain.order.dto.ConfirmPaymentRequest;
import com.kitschcatch.backend.domain.order.dto.CreatePaymentRequest;
import com.kitschcatch.backend.domain.order.dto.CreatePaymentResponse;
import com.kitschcatch.backend.domain.order.dto.PaymentResponse;
import com.kitschcatch.backend.domain.order.entity.OrderStatus;
import com.kitschcatch.backend.domain.order.entity.Payment;
import com.kitschcatch.backend.domain.order.entity.PaymentStatus;
import com.kitschcatch.backend.domain.order.entity.PurchaseOrder;
import com.kitschcatch.backend.domain.order.repository.PaymentRepository;
import com.kitschcatch.backend.domain.order.repository.PurchaseOrderRepository;
import com.kitschcatch.backend.global.exception.BusinessException;
import com.kitschcatch.backend.global.exception.ErrorCode;
import java.util.Locale;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PaymentTransactionService {

	private final PaymentRepository paymentRepository;
	private final PurchaseOrderRepository orderRepository;

	public PaymentTransactionService(
		PaymentRepository paymentRepository,
		PurchaseOrderRepository orderRepository
	) {
		this.paymentRepository = paymentRepository;
		this.orderRepository = orderRepository;
	}

	@Transactional
	public CreatePaymentResponse createPayment(Long userId, CreatePaymentRequest request) {
		PurchaseOrder order = orderRepository.findByOrderNumberAndUserId(request.orderId(), userId)
			.orElseThrow(() -> new BusinessException(ErrorCode.ORDER_NOT_FOUND));
		validateAmount(request.amount(), order.getAmount());
		validateOrderPending(order);

		Payment payment = paymentRepository.save(Payment.builder()
			.paymentId(generatePaymentId())
			.order(order)
			.amount(request.amount())
			.paymentMethod(request.paymentMethod())
			.paymentStatus(PaymentStatus.READY)
			.build());
		return new CreatePaymentResponse(payment.getPaymentId(), payment.getPaymentStatus());
	}

	@Transactional(readOnly = true)
	public PaymentResponse getPayment(Long userId, String paymentId) {
		return toResponse(findPayment(paymentId, userId));
	}

	@Transactional
	public PaymentOperationContext startConfirm(Long userId, String paymentId, ConfirmPaymentRequest request) {
		if (!paymentId.equals(request.paymentId())) {
			throw new BusinessException(ErrorCode.PAYMENT_NOT_FOUND);
		}
		Payment payment = findPaymentWithLock(paymentId, userId);
		if (payment.getPaymentStatus() != PaymentStatus.READY) {
			throw new BusinessException(ErrorCode.PAYMENT_INVALID_STATUS);
		}
		if (payment.getOrder().getOrderStatus() != OrderStatus.PENDING) {
			throw new BusinessException(ErrorCode.PAYMENT_INVALID_STATUS);
		}

		payment.startProcessing();
		return new PaymentOperationContext(
			payment.getOrder().getOrderNumber(),
			payment.getAmount(),
			request.paymentKey(),
			PaymentStatus.READY
		);
	}

	@Transactional
	public PaymentResponse completeConfirm(Long userId, String paymentId, String paymentKey) {
		Payment payment = findPaymentWithLock(paymentId, userId);
		validateProcessing(payment);
		payment.confirm(paymentKey);
		return toResponse(payment);
	}

	@Transactional
	public PaymentOperationContext startCancel(Long userId, String paymentId) {
		Payment payment = findPaymentWithLock(paymentId, userId);
		if (payment.getPaymentStatus() == PaymentStatus.CANCELED) {
			throw new BusinessException(ErrorCode.PAYMENT_INVALID_STATUS);
		}
		if (payment.getPaymentStatus() != PaymentStatus.SUCCESS || payment.getPaymentKey() == null) {
			throw new BusinessException(ErrorCode.PAYMENT_INVALID_STATUS);
		}

		String paymentKey = payment.getPaymentKey();
		payment.startProcessing();
		return new PaymentOperationContext(
			payment.getOrder().getOrderNumber(),
			payment.getAmount(),
			paymentKey,
			PaymentStatus.SUCCESS
		);
	}

	@Transactional
	public PaymentResponse completeCancel(Long userId, String paymentId) {
		Payment payment = findPaymentWithLock(paymentId, userId);
		validateProcessing(payment);
		payment.cancel();
		return toResponse(payment);
	}

	@Transactional
	public void restoreProcessing(Long userId, String paymentId, PaymentStatus rollbackStatus) {
		Payment payment = findPaymentWithLock(paymentId, userId);
		if (payment.getPaymentStatus() == PaymentStatus.PROCESSING) {
			payment.restoreStatus(rollbackStatus);
		}
	}

	private Payment findPayment(String paymentId, Long userId) {
		return paymentRepository.findByPaymentIdAndOrderUserId(paymentId, userId)
			.orElseThrow(() -> new BusinessException(ErrorCode.PAYMENT_NOT_FOUND));
	}

	private Payment findPaymentWithLock(String paymentId, Long userId) {
		return paymentRepository.findByPaymentIdAndOrderUserIdWithLock(paymentId, userId)
			.orElseThrow(() -> new BusinessException(ErrorCode.PAYMENT_NOT_FOUND));
	}

	private void validateAmount(Long requestedAmount, Long orderAmount) {
		if (!orderAmount.equals(requestedAmount)) {
			throw new BusinessException(ErrorCode.PAYMENT_AMOUNT_MISMATCH);
		}
	}

	private void validateOrderPending(PurchaseOrder order) {
		if (order.getOrderStatus() != OrderStatus.PENDING) {
			throw new BusinessException(ErrorCode.PAYMENT_INVALID_STATUS);
		}
	}

	private void validateProcessing(Payment payment) {
		if (payment.getPaymentStatus() != PaymentStatus.PROCESSING) {
			throw new BusinessException(ErrorCode.PAYMENT_INVALID_STATUS);
		}
	}

	private PaymentResponse toResponse(Payment payment) {
		return new PaymentResponse(
			payment.getPaymentId(),
			payment.getOrder().getOrderNumber(),
			payment.getAmount(),
			payment.getPaymentStatus(),
			payment.getCreatedAt(),
			payment.getApprovedAt()
		);
	}

	private String generatePaymentId() {
		String suffix = UUID.randomUUID().toString()
			.replace("-", "")
			.toUpperCase(Locale.ROOT);
		return "PAY-" + suffix;
	}
}
