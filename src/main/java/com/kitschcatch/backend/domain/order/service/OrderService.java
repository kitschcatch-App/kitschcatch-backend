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
import com.kitschcatch.backend.domain.post.entity.ProductStatus;
import com.kitschcatch.backend.domain.post.repository.PostRepository;
import com.kitschcatch.backend.domain.user.entity.User;
import com.kitschcatch.backend.domain.user.repository.UserRepository;
import com.kitschcatch.backend.global.exception.BusinessException;
import com.kitschcatch.backend.global.exception.ErrorCode;
import java.util.Locale;
import java.util.List;
import java.util.UUID;
import java.time.LocalDateTime;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@EnableConfigurationProperties(OrderReservationProperties.class)
public class OrderService {

	private final PurchaseOrderRepository orderRepository;
	private final PaymentRepository paymentRepository;
	private final PostRepository postRepository;
	private final UserRepository userRepository;
	private final OrderReservationProperties reservationProperties;

	public OrderService(
		PurchaseOrderRepository orderRepository,
		PaymentRepository paymentRepository,
		PostRepository postRepository,
		UserRepository userRepository,
		OrderReservationProperties reservationProperties
	) {
		this.orderRepository = orderRepository;
		this.paymentRepository = paymentRepository;
		this.postRepository = postRepository;
		this.userRepository = userRepository;
		this.reservationProperties = reservationProperties;
	}

	@Transactional
	public CreateOrderResponse createOrder(Long userId, CreateOrderRequest request) {
		User buyer = userRepository.findById(userId)
			.orElseThrow(() -> new BusinessException(ErrorCode.INVALID_AUTH_TOKEN));
		Post post = postRepository.findByIdForUpdate(request.postId())
			.orElseThrow(() -> new BusinessException(ErrorCode.POST_NOT_FOUND));
		if (post.getDeletedAt() != null) {
			throw new BusinessException(ErrorCode.POST_NOT_FOUND);
		}

		validatePostOnSale(post);
		validateNotSeller(userId, post);
		validateAmount(request.amount(), post.getPrice());
		if (post.getActiveOrderNumber() != null || orderRepository.existsByPostIdAndOrderStatusIn(
			post.getId(), List.of(OrderStatus.PENDING, OrderStatus.PAID))) {
			throw new BusinessException(ErrorCode.POST_TRANSACTION_IN_PROGRESS);
		}

		PurchaseOrder order = orderRepository.save(PurchaseOrder.builder()
			.orderNumber(generateId("ORD"))
			.user(buyer)
			.post(post)
			.amount(request.amount())
			.pgProvider(PgProvider.TOSS_PAYMENTS)
			.orderStatus(OrderStatus.PENDING)
			.reservationExpiresAt(LocalDateTime.now().plus(reservationProperties.reservationTtl()))
			.build());
		post.reserve(order.getOrderNumber());
		Payment payment = paymentRepository.save(Payment.builder()
			.paymentId(generateId("PAY"))
			.order(order)
			.amount(order.getAmount())
			.paymentMethod(request.paymentMethod())
			.paymentStatus(PaymentStatus.READY)
			.build());

		return new CreateOrderResponse(order.getOrderNumber(), payment.getPaymentId(), payment.getPaymentStatus());
	}

	private void validatePostOnSale(Post post) {
		if (post.getProductStatus() != ProductStatus.ON_SALE) {
			throw new BusinessException(ErrorCode.BAD_REQUEST, "판매 중인 상품만 주문할 수 있습니다.");
		}
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
			.toUpperCase(Locale.ROOT);
		return prefix + "-" + suffix;
	}
}
