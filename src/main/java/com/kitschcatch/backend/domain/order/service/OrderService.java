// 주문 생성과 초기 결제 대기 정보 생성을 담당하는 서비스
package com.kitschcatch.backend.domain.order.service;

import com.kitschcatch.backend.domain.order.dto.CreateOrderRequest;
import com.kitschcatch.backend.domain.order.dto.CreateOrderResponse;
import com.kitschcatch.backend.domain.order.entity.OrderStatus;
import com.kitschcatch.backend.domain.order.entity.Payment;
import com.kitschcatch.backend.domain.order.entity.PaymentStatus;
import com.kitschcatch.backend.domain.order.entity.PgProvider;
import com.kitschcatch.backend.domain.order.entity.PurchaseOrder;
import com.kitschcatch.backend.domain.order.repository.PaymentRepository;
import com.kitschcatch.backend.domain.order.repository.PurchaseOrderRepository;
import com.kitschcatch.backend.domain.post.entity.Post;
import com.kitschcatch.backend.domain.post.repository.PostRepository;
import com.kitschcatch.backend.domain.user.entity.User;
import com.kitschcatch.backend.domain.user.repository.UserRepository;
import com.kitschcatch.backend.global.exception.BusinessException;
import com.kitschcatch.backend.global.exception.ErrorCode;
import java.util.Locale;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrderService {

	private final PurchaseOrderRepository orderRepository;
	private final PaymentRepository paymentRepository;
	private final PostRepository postRepository;
	private final UserRepository userRepository;

	public OrderService(
		PurchaseOrderRepository orderRepository,
		PaymentRepository paymentRepository,
		PostRepository postRepository,
		UserRepository userRepository
	) {
		this.orderRepository = orderRepository;
		this.paymentRepository = paymentRepository;
		this.postRepository = postRepository;
		this.userRepository = userRepository;
	}

	@Transactional
	public CreateOrderResponse createOrder(Long userId, CreateOrderRequest request) {
		User buyer = userRepository.findById(userId)
			.orElseThrow(() -> new BusinessException(ErrorCode.INVALID_AUTH_TOKEN));
		Post post = postRepository.findByIdAndDeletedAtIsNull(request.postId())
			.orElseThrow(() -> new BusinessException(ErrorCode.POST_NOT_FOUND));

		validateNotSeller(userId, post);
		validateAmount(request.amount(), post.getPrice());

		PurchaseOrder order = orderRepository.save(PurchaseOrder.builder()
			.orderNumber(generateId("ORD"))
			.user(buyer)
			.post(post)
			.amount(request.amount())
			.pgProvider(PgProvider.TOSS_PAYMENTS)
			.orderStatus(OrderStatus.PENDING)
			.build());
		Payment payment = paymentRepository.save(Payment.builder()
			.paymentId(generateId("PAY"))
			.order(order)
			.amount(order.getAmount())
			.paymentMethod(request.paymentMethod())
			.paymentStatus(PaymentStatus.READY)
			.build());

		return new CreateOrderResponse(order.getOrderNumber(), payment.getPaymentId(), payment.getPaymentStatus());
	}

	private void validateNotSeller(Long userId, Post post) {
		if (post.getUser().getId().equals(userId)) {
			throw new BusinessException(ErrorCode.POST_FORBIDDEN, "본인 판매 게시글은 주문할 수 없습니다.");
		}
	}

	private void validateAmount(Long requestedAmount, Long postPrice) {
		if (!postPrice.equals(requestedAmount)) {
			throw new BusinessException(ErrorCode.PAYMENT_AMOUNT_MISMATCH);
		}
	}

	private String generateId(String prefix) {
		String suffix = UUID.randomUUID().toString()
			.replace("-", "")
			.substring(0, 12)
			.toUpperCase(Locale.ROOT);
		return prefix + "-" + suffix;
	}
}
