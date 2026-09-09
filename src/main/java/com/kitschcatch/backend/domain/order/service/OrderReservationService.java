// 상품과 주문을 같은 순서로 잠그고 미결제 주문의 예약을 만료시킨다.
package com.kitschcatch.backend.domain.order.service;

import com.kitschcatch.backend.domain.order.entity.OrderStatus;
import com.kitschcatch.backend.domain.order.entity.Payment;
import com.kitschcatch.backend.domain.order.entity.PaymentStatus;
import com.kitschcatch.backend.domain.order.entity.PurchaseOrder;
import com.kitschcatch.backend.domain.order.repository.PaymentRepository;
import com.kitschcatch.backend.domain.order.repository.PurchaseOrderRepository;
import com.kitschcatch.backend.domain.post.repository.PostRepository;
import com.kitschcatch.backend.global.exception.BusinessException;
import com.kitschcatch.backend.global.exception.ErrorCode;
import java.time.LocalDateTime;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrderReservationService {
	private final PostRepository postRepository;
	private final PurchaseOrderRepository orderRepository;
	private final PaymentRepository paymentRepository;

	public OrderReservationService(PostRepository postRepository, PurchaseOrderRepository orderRepository,
		PaymentRepository paymentRepository) {
		this.postRepository = postRepository;
		this.orderRepository = orderRepository;
		this.paymentRepository = paymentRepository;
	}

	@Transactional(propagation = Propagation.MANDATORY)
	public PurchaseOrder lockOrder(Long orderId) {
		Long postId = orderRepository.findPostIdById(orderId)
			.orElseThrow(() -> new BusinessException(ErrorCode.ORDER_NOT_FOUND));
		postRepository.findByIdForUpdate(postId)
			.orElseThrow(() -> new BusinessException(ErrorCode.POST_NOT_FOUND));
		return orderRepository.findByIdForUpdate(orderId)
			.orElseThrow(() -> new BusinessException(ErrorCode.ORDER_NOT_FOUND));
	}

	@Transactional
	public boolean expireOrder(Long orderId) {
		PurchaseOrder order = lockOrder(orderId);
		if (order.getOrderStatus() != OrderStatus.PENDING || !order.isReservationExpired(LocalDateTime.now())
			|| !order.getPost().isOwnedByOrder(order.getOrderNumber())) {
			return false;
		}
		Payment payment = paymentRepository.findByOrderIdForUpdate(orderId).orElse(null);
		if (payment == null || payment.getPaymentStatus() != PaymentStatus.READY) {
			return false;
		}
		payment.cancel();
		order.getPost().releaseOrder(order.getOrderNumber());
		return true;
	}
}
