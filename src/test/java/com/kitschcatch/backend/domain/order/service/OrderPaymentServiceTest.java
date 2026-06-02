// 주문과 결제 서비스의 상태 전이를 검증하는 테스트
package com.kitschcatch.backend.domain.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.kitschcatch.backend.domain.order.dto.ConfirmPaymentRequest;
import com.kitschcatch.backend.domain.order.dto.CreateOrderRequest;
import com.kitschcatch.backend.domain.order.dto.CreatePaymentRequest;
import com.kitschcatch.backend.domain.order.dto.PaymentResponse;
import com.kitschcatch.backend.domain.order.entity.OrderStatus;
import com.kitschcatch.backend.domain.order.entity.Payment;
import com.kitschcatch.backend.domain.order.entity.PaymentMethod;
import com.kitschcatch.backend.domain.order.entity.PaymentStatus;
import com.kitschcatch.backend.domain.order.entity.PgProvider;
import com.kitschcatch.backend.domain.order.entity.PurchaseOrder;
import com.kitschcatch.backend.domain.order.repository.PaymentRepository;
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
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class OrderPaymentServiceTest {

	private PurchaseOrderRepository orderRepository;
	private PaymentRepository paymentRepository;
	private PostRepository postRepository;
	private UserRepository userRepository;
	private OrderService orderService;
	private PaymentService paymentService;
	private User buyer;
	private User seller;
	private Post post;

	@BeforeEach
	void setUp() {
		orderRepository = mock(PurchaseOrderRepository.class);
		paymentRepository = mock(PaymentRepository.class);
		postRepository = mock(PostRepository.class);
		userRepository = mock(UserRepository.class);
		orderService = new OrderService(orderRepository, paymentRepository, postRepository, userRepository);
		paymentService = new PaymentService(paymentRepository, orderRepository);

		buyer = user(1L, "buyer", "buyer@example.com", "buyer-provider");
		seller = user(2L, "seller", "seller@example.com", "seller-provider");
		post = post(10L, seller, 650000L);
	}

	@Test
	@DisplayName("주문 생성은 주문 번호와 결제 ID를 문자열로 저장한다")
	void createOrderStoresStringOrderAndPaymentIds() {
		when(userRepository.findById(1L)).thenReturn(Optional.of(buyer));
		when(postRepository.findByIdAndDeletedAtIsNull(10L)).thenReturn(Optional.of(post));
		when(orderRepository.save(any(PurchaseOrder.class))).thenAnswer(invocation -> {
			PurchaseOrder order = invocation.getArgument(0);
			ReflectionTestUtils.setField(order, "id", 1L);
			return order;
		});
		when(paymentRepository.save(any(Payment.class))).thenAnswer(invocation -> {
			Payment payment = invocation.getArgument(0);
			ReflectionTestUtils.setField(payment, "id", 1L);
			return payment;
		});

		var response = orderService.createOrder(1L, new CreateOrderRequest(10L, 650000L, PaymentMethod.CARD));

		assertThat(response.orderId()).startsWith("ORD-");
		assertThat(response.paymentId()).startsWith("PAY-");
		assertThat(response.status()).isEqualTo(PaymentStatus.READY);
	}

	@Test
	@DisplayName("결제 생성은 주문 번호로 주문을 찾아 결제 대기 상태를 저장한다")
	void createPaymentStoresReadyPaymentForOrderNumber() {
		PurchaseOrder order = order("ORD-123", buyer, post, OrderStatus.PENDING);
		when(orderRepository.findByOrderNumberAndUserId("ORD-123", 1L)).thenReturn(Optional.of(order));
		when(paymentRepository.save(any(Payment.class))).thenAnswer(invocation -> invocation.getArgument(0));

		var response = paymentService.createPayment(
			1L,
			new CreatePaymentRequest("ORD-123", 650000L, PaymentMethod.CARD)
		);

		assertThat(response.paymentId()).startsWith("PAY-");
		assertThat(response.status()).isEqualTo(PaymentStatus.READY);
	}

	@Test
	@DisplayName("결제 승인은 결제를 성공으로 바꾸고 주문을 결제 완료로 바꾼다")
	void confirmPaymentMarksPaymentAndOrderPaid() {
		PurchaseOrder order = order("ORD-123", buyer, post, OrderStatus.PENDING);
		Payment payment = payment("PAY-999", order, PaymentStatus.READY);
		when(paymentRepository.findByPaymentIdAndOrderUserId("PAY-999", 1L)).thenReturn(Optional.of(payment));

		PaymentResponse response = paymentService.confirmPayment(
			1L,
			"PAY-999",
			new ConfirmPaymentRequest("PAY-999", "pg-token")
		);

		assertThat(response.status()).isEqualTo(PaymentStatus.SUCCESS);
		assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.PAID);
	}

	@Test
	@DisplayName("결제 취소는 결제를 취소로 바꾸고 주문을 취소로 바꾼다")
	void cancelPaymentMarksPaymentAndOrderCanceled() {
		PurchaseOrder order = order("ORD-123", buyer, post, OrderStatus.PAID);
		Payment payment = payment("PAY-999", order, PaymentStatus.SUCCESS);
		when(paymentRepository.findByPaymentIdAndOrderUserId("PAY-999", 1L)).thenReturn(Optional.of(payment));

		PaymentResponse response = paymentService.cancelPayment(1L, "PAY-999");

		assertThat(response.status()).isEqualTo(PaymentStatus.CANCELED);
		assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.CANCELED);
	}

	@Test
	@DisplayName("결제 생성은 주문 금액과 결제 금액이 다르면 예외를 던진다")
	void createPaymentWithDifferentAmountThrowsException() {
		PurchaseOrder order = order("ORD-123", buyer, post, OrderStatus.PENDING);
		when(orderRepository.findByOrderNumberAndUserId("ORD-123", 1L)).thenReturn(Optional.of(order));

		assertThatThrownBy(() -> paymentService.createPayment(
			1L,
			new CreatePaymentRequest("ORD-123", 1L, PaymentMethod.CARD)
		))
			.isInstanceOf(BusinessException.class)
			.extracting("errorCode")
			.isEqualTo(ErrorCode.PAYMENT_AMOUNT_MISMATCH);
	}

	private User user(Long id, String nickname, String email, String providerUserId) {
		User user = User.builder()
			.nickname(nickname)
			.email(email)
			.authProvider(AuthProvider.KAKAO)
			.providerUserId(providerUserId)
			.build();
		ReflectionTestUtils.setField(user, "id", id);
		return user;
	}

	private Post post(Long id, User seller, Long price) {
		Post post = Post.builder()
			.user(seller)
			.title("피규어 판매")
			.description("미개봉 상품")
			.price(price)
			.productCategory(ProductCategory.GOODS)
			.productCondition(ProductCondition.NEW)
			.productStatus(ProductStatus.ON_SALE)
			.build();
		ReflectionTestUtils.setField(post, "id", id);
		return post;
	}

	private PurchaseOrder order(String orderNumber, User user, Post post, OrderStatus orderStatus) {
		PurchaseOrder order = PurchaseOrder.builder()
			.orderNumber(orderNumber)
			.user(user)
			.post(post)
			.amount(post.getPrice())
			.pgProvider(PgProvider.TOSS_PAYMENTS)
			.orderStatus(orderStatus)
			.build();
		ReflectionTestUtils.setField(order, "createdAt", LocalDateTime.of(2026, 4, 13, 15, 0));
		return order;
	}

	private Payment payment(String paymentId, PurchaseOrder order, PaymentStatus paymentStatus) {
		Payment payment = Payment.builder()
			.paymentId(paymentId)
			.order(order)
			.amount(order.getAmount())
			.paymentMethod(PaymentMethod.CARD)
			.paymentStatus(paymentStatus)
			.build();
		ReflectionTestUtils.setField(payment, "createdAt", LocalDateTime.of(2026, 4, 13, 15, 0));
		return payment;
	}
}
