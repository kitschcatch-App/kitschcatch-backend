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
import com.kitschcatch.backend.domain.post.entity.ProductStatus;
import com.kitschcatch.backend.global.exception.BusinessException;
import com.kitschcatch.backend.global.exception.ErrorCode;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PaymentTransactionService {

	private final PaymentRepository paymentRepository;
	private final PurchaseOrderRepository orderRepository;
	private final OrderReservationService reservationService;

	public PaymentTransactionService(
		PaymentRepository paymentRepository,
		PurchaseOrderRepository orderRepository,
		OrderReservationService reservationService
	) {
		this.paymentRepository = paymentRepository;
		this.orderRepository = orderRepository;
		this.reservationService = reservationService;
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
			return new CreatePaymentResponse(existing.getPaymentId(), existing.getPaymentStatus());
		}
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
		return toResponse(paymentRepository.findByPaymentIdAndOrderUserId(paymentId, userId)
			.orElseThrow(() -> new BusinessException(ErrorCode.PAYMENT_NOT_FOUND)));
	}

	@Transactional
	public PaymentOperationContext startConfirm(Long userId, String paymentId, ConfirmPaymentRequest request) {
		if (!paymentId.equals(request.paymentId())) {
			throw new BusinessException(ErrorCode.PAYMENT_NOT_FOUND);
		}
		Payment payment = findPaymentWithLock(paymentId, userId);
		validatePaymentStatus(payment, PaymentStatus.READY);
		validateOrderStatus(payment.getOrder(), OrderStatus.PENDING);
		validateReservation(payment.getOrder(), ProductStatus.RESERVED);
		validateNotExpired(payment.getOrder());

		payment.startConfirmation(request.paymentKey());
		return operationContext(payment);
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
		payment.startProcessing();
		return operationContext(payment);
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

	private PaymentOperationContext operationContext(Payment payment) {
		return new PaymentOperationContext(payment.getOrder().getOrderNumber(), payment.getAmount(), payment.getPaymentKey());
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
