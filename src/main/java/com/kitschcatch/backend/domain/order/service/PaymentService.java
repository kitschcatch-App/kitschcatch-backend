// 주문 결제의 생성, 승인, 조회, 취소 상태 전이를 담당하는 서비스
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
public class PaymentService {

	private final PaymentRepository paymentRepository;
	private final PurchaseOrderRepository orderRepository;

	public PaymentService(PaymentRepository paymentRepository, PurchaseOrderRepository orderRepository) {
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

	@Transactional
	public PaymentResponse confirmPayment(Long userId, String paymentId, ConfirmPaymentRequest request) {
		if (!paymentId.equals(request.paymentId())) {
			throw new BusinessException(ErrorCode.PAYMENT_NOT_FOUND);
		}
		Payment payment = findPayment(paymentId, userId);
		if (payment.getPaymentStatus() != PaymentStatus.READY) {
			throw new BusinessException(ErrorCode.PAYMENT_INVALID_STATUS);
		}

		payment.confirm(request.paymentToken());
		return toResponse(payment);
	}

	@Transactional(readOnly = true)
	public PaymentResponse getPayment(Long userId, String paymentId) {
		return toResponse(findPayment(paymentId, userId));
	}

	@Transactional
	public PaymentResponse cancelPayment(Long userId, String paymentId) {
		Payment payment = findPayment(paymentId, userId);
		if (payment.getPaymentStatus() == PaymentStatus.CANCELED) {
			throw new BusinessException(ErrorCode.PAYMENT_INVALID_STATUS);
		}

		payment.cancel();
		return toResponse(payment);
	}

	private Payment findPayment(String paymentId, Long userId) {
		return paymentRepository.findByPaymentIdAndOrderUserId(paymentId, userId)
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
			.substring(0, 12)
			.toUpperCase(Locale.ROOT);
		return "PAY-" + suffix;
	}
}
