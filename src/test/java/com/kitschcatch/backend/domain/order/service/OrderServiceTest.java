package com.kitschcatch.backend.domain.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.kitschcatch.backend.domain.order.dto.CreateOrderRequest;
import com.kitschcatch.backend.domain.order.dto.OrderResponse;
import com.kitschcatch.backend.domain.order.entity.OrderStatus;
import com.kitschcatch.backend.domain.order.entity.PurchaseOrder;
import com.kitschcatch.backend.domain.order.repository.PurchaseOrderRepository;
import com.kitschcatch.backend.domain.post.entity.Post;
import com.kitschcatch.backend.domain.post.entity.ProductCategory;
import com.kitschcatch.backend.domain.post.entity.ProductCondition;
import com.kitschcatch.backend.domain.post.entity.ProductStatus;
import com.kitschcatch.backend.domain.post.repository.PostRepository;
import com.kitschcatch.backend.domain.user.entity.AuthProvider;
import com.kitschcatch.backend.domain.user.entity.User;
import com.kitschcatch.backend.domain.user.repository.UserRepository;
import com.kitschcatch.backend.global.exception.BusinessException;
import com.kitschcatch.backend.global.exception.ErrorCode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class OrderServiceTest {

	private PurchaseOrderRepository orderRepository;
	private PostRepository postRepository;
	private UserRepository userRepository;
	private OrderService orderService;
	private User buyer;
	private User seller;
	private Clock clock;

	@BeforeEach
	void setUp() {
		orderRepository = mock(PurchaseOrderRepository.class);
		postRepository = mock(PostRepository.class);
		userRepository = mock(UserRepository.class);
		clock = Clock.fixed(Instant.parse("2026-05-21T05:00:00Z"), ZoneId.of("Asia/Seoul"));
		orderService = new OrderService(orderRepository, postRepository, userRepository, clock);
		buyer = user(2L, "buyer");
		seller = user(1L, "seller");
	}

	@Test
	@DisplayName("주문 생성은 판매글 금액으로 주문을 만들고 상품을 15분 예약한다")
	void createOrderReservesPostForFifteenMinutes() {
		Post post = post(ProductStatus.ON_SALE);
		when(userRepository.findById(2L)).thenReturn(Optional.of(buyer));
		when(postRepository.findByIdAndDeletedAtIsNullForUpdate(10L)).thenReturn(Optional.of(post));
		when(orderRepository.findFirstByPostIdAndOrderStatusOrderByCreatedAtDesc(10L, OrderStatus.PENDING))
			.thenReturn(Optional.empty());
		when(orderRepository.save(any(PurchaseOrder.class))).thenAnswer(invocation -> {
			PurchaseOrder order = invocation.getArgument(0);
			ReflectionTestUtils.setField(order, "id", 30L);
			return order;
		});

		OrderResponse response = orderService.createOrder(2L, new CreateOrderRequest(10L));

		assertThat(post.getProductStatus()).isEqualTo(ProductStatus.RESERVED);
		assertThat(response.id()).isEqualTo(30L);
		assertThat(response.amount()).isEqualTo(12000L);
		assertThat(response.orderStatus()).isEqualTo(OrderStatus.PENDING);
		assertThat(response.expiresAt()).isEqualTo(LocalDateTime.of(2026, 5, 21, 14, 15));
		verify(orderRepository).save(any(PurchaseOrder.class));
	}

	@Test
	@DisplayName("만료된 대기 주문이 있으면 만료 처리하고 상품을 다시 예약한다")
	void createOrderExpiresPreviousPendingOrder() {
		Post post = post(ProductStatus.RESERVED);
		PurchaseOrder expiredOrder = pendingOrder(post, LocalDateTime.of(2026, 5, 21, 13, 59));
		when(userRepository.findById(2L)).thenReturn(Optional.of(buyer));
		when(postRepository.findByIdAndDeletedAtIsNullForUpdate(10L)).thenReturn(Optional.of(post));
		when(orderRepository.findFirstByPostIdAndOrderStatusOrderByCreatedAtDesc(10L, OrderStatus.PENDING))
			.thenReturn(Optional.of(expiredOrder));
		when(orderRepository.save(any(PurchaseOrder.class))).thenAnswer(invocation -> invocation.getArgument(0));

		orderService.createOrder(2L, new CreateOrderRequest(10L));

		assertThat(expiredOrder.getOrderStatus()).isEqualTo(OrderStatus.EXPIRED);
		assertThat(post.getProductStatus()).isEqualTo(ProductStatus.RESERVED);
	}

	@Test
	@DisplayName("만료되지 않은 대기 주문이 있으면 새 주문을 거부한다")
	void createOrderRejectsReservedPost() {
		Post post = post(ProductStatus.RESERVED);
		PurchaseOrder pendingOrder = pendingOrder(post, LocalDateTime.of(2026, 5, 21, 14, 10));
		when(userRepository.findById(2L)).thenReturn(Optional.of(buyer));
		when(postRepository.findByIdAndDeletedAtIsNullForUpdate(10L)).thenReturn(Optional.of(post));
		when(orderRepository.findFirstByPostIdAndOrderStatusOrderByCreatedAtDesc(10L, OrderStatus.PENDING))
			.thenReturn(Optional.of(pendingOrder));

		assertThatThrownBy(() -> orderService.createOrder(2L, new CreateOrderRequest(10L)))
			.isInstanceOf(BusinessException.class)
			.extracting("errorCode")
			.isEqualTo(ErrorCode.ORDER_UNAVAILABLE);
	}

	@Test
	@DisplayName("판매자는 본인 판매글 주문을 생성할 수 없다")
	void createOrderRejectsSellerOwnPost() {
		Post post = post(ProductStatus.ON_SALE);
		when(userRepository.findById(1L)).thenReturn(Optional.of(seller));
		when(postRepository.findByIdAndDeletedAtIsNullForUpdate(10L)).thenReturn(Optional.of(post));
		when(orderRepository.findFirstByPostIdAndOrderStatusOrderByCreatedAtDesc(10L, OrderStatus.PENDING))
			.thenReturn(Optional.empty());
		when(orderRepository.save(any(PurchaseOrder.class))).thenAnswer(invocation -> invocation.getArgument(0));

		assertThatThrownBy(() -> orderService.createOrder(1L, new CreateOrderRequest(10L)))
			.isInstanceOf(BusinessException.class)
			.extracting("errorCode")
			.isEqualTo(ErrorCode.ORDER_UNAVAILABLE);

		assertThat(post.getProductStatus()).isEqualTo(ProductStatus.ON_SALE);
		verify(orderRepository, never()).save(any(PurchaseOrder.class));
	}

	@Test
	@DisplayName("결제 완료 주문은 만료 처리할 수 없다")
	void paidOrderCannotExpire() {
		PurchaseOrder paidOrder = pendingOrder(post(ProductStatus.RESERVED), LocalDateTime.of(2026, 5, 21, 14, 10));
		paidOrder.markPaid();

		assertThatThrownBy(paidOrder::expire)
			.isInstanceOf(BusinessException.class)
			.satisfies(exception -> assertThat(((BusinessException) exception).getErrorCode().name())
				.isEqualTo("ORDER_INVALID_STATE"));
	}

	@Test
	@DisplayName("만료 주문은 결제 완료 처리할 수 없다")
	void expiredOrderCannotBeMarkedPaid() {
		PurchaseOrder expiredOrder = pendingOrder(post(ProductStatus.RESERVED), LocalDateTime.of(2026, 5, 21, 13, 59));
		expiredOrder.expire();

		assertThatThrownBy(expiredOrder::markPaid)
			.isInstanceOf(BusinessException.class)
			.satisfies(exception -> assertThat(((BusinessException) exception).getErrorCode().name())
				.isEqualTo("ORDER_INVALID_STATE"));
	}

	@Test
	@DisplayName("대기 주문은 결제 취소 처리할 수 없다")
	void pendingOrderCannotBeCanceled() {
		PurchaseOrder pendingOrder = pendingOrder(post(ProductStatus.RESERVED), LocalDateTime.of(2026, 5, 21, 14, 10));

		assertThatThrownBy(pendingOrder::cancel)
			.isInstanceOf(BusinessException.class)
			.satisfies(exception -> assertThat(((BusinessException) exception).getErrorCode().name())
				.isEqualTo("ORDER_INVALID_STATE"));
	}

	private PurchaseOrder pendingOrder(Post post, LocalDateTime expiresAt) {
		return PurchaseOrder.builder()
			.user(buyer)
			.post(post)
			.amount(12000L)
			.orderStatus(OrderStatus.PENDING)
			.expiresAt(expiresAt)
			.build();
	}

	private Post post(ProductStatus status) {
		Post post = Post.builder()
			.user(seller)
			.title("키링")
			.description("미개봉 상품")
			.price(12000L)
			.productCategory(ProductCategory.GOODS)
			.productCondition(ProductCondition.NEW)
			.productStatus(status)
			.build();
		ReflectionTestUtils.setField(post, "id", 10L);
		return post;
	}

	private User user(Long id, String nickname) {
		User user = User.builder()
			.nickname(nickname)
			.email(nickname + "@example.com")
			.authProvider(AuthProvider.KAKAO)
			.providerUserId(nickname + "-provider")
			.build();
		ReflectionTestUtils.setField(user, "id", id);
		return user;
	}
}
