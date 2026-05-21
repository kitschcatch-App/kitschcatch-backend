package com.kitschcatch.backend.domain.order.service;

import com.kitschcatch.backend.domain.order.dto.CreateOrderRequest;
import com.kitschcatch.backend.domain.order.dto.OrderResponse;
import com.kitschcatch.backend.domain.order.entity.OrderStatus;
import com.kitschcatch.backend.domain.order.entity.PurchaseOrder;
import com.kitschcatch.backend.domain.order.repository.PurchaseOrderRepository;
import com.kitschcatch.backend.domain.post.entity.Post;
import com.kitschcatch.backend.domain.post.entity.ProductStatus;
import com.kitschcatch.backend.domain.post.repository.PostRepository;
import com.kitschcatch.backend.domain.user.entity.User;
import com.kitschcatch.backend.domain.user.repository.UserRepository;
import com.kitschcatch.backend.global.exception.BusinessException;
import com.kitschcatch.backend.global.exception.ErrorCode;
import java.time.Clock;
import java.time.LocalDateTime;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrderService {

	private static final long ORDER_HOLD_MINUTES = 15;

	private final PurchaseOrderRepository orderRepository;
	private final PostRepository postRepository;
	private final UserRepository userRepository;
	private final Clock clock;

	@Autowired
	public OrderService(
		PurchaseOrderRepository orderRepository,
		PostRepository postRepository,
		UserRepository userRepository
	) {
		this(orderRepository, postRepository, userRepository, Clock.systemDefaultZone());
	}

	OrderService(
		PurchaseOrderRepository orderRepository,
		PostRepository postRepository,
		UserRepository userRepository,
		Clock clock
	) {
		this.orderRepository = orderRepository;
		this.postRepository = postRepository;
		this.userRepository = userRepository;
		this.clock = clock;
	}

	@Transactional
	public OrderResponse createOrder(Long userId, CreateOrderRequest request) {
		User user = userRepository.findById(userId)
			.orElseThrow(() -> new BusinessException(ErrorCode.INVALID_AUTH_TOKEN));
		Post post = postRepository.findByIdAndDeletedAtIsNullForUpdate(request.postId())
			.orElseThrow(() -> new BusinessException(ErrorCode.POST_NOT_FOUND));
		LocalDateTime now = LocalDateTime.now(clock);

		orderRepository.findFirstByPostIdAndOrderStatusOrderByCreatedAtDesc(post.getId(), OrderStatus.PENDING)
			.ifPresent(order -> expireOrReject(order, post, now));

		if (post.getProductStatus() != ProductStatus.ON_SALE) {
			throw new BusinessException(ErrorCode.ORDER_UNAVAILABLE);
		}

		post.reserve();
		PurchaseOrder order = PurchaseOrder.pending(
			user,
			post,
			post.getPrice(),
			now.plusMinutes(ORDER_HOLD_MINUTES)
		);
		return toResponse(orderRepository.save(order));
	}

	private void expireOrReject(PurchaseOrder order, Post post, LocalDateTime now) {
		if (!order.isExpired(now)) {
			throw new BusinessException(ErrorCode.ORDER_UNAVAILABLE);
		}
		order.expire();
		post.reopen();
	}

	private OrderResponse toResponse(PurchaseOrder order) {
		return new OrderResponse(
			order.getId(),
			order.getPost().getId(),
			order.getUser().getId(),
			order.getAmount(),
			order.getOrderStatus(),
			order.getExpiresAt(),
			order.getCreatedAt(),
			order.getUpdatedAt()
		);
	}
}
