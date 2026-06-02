// 주문과 결제 서비스의 상태 전이를 검증하는 테스트
package com.kitschcatch.backend.domain.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
import com.kitschcatch.backend.domain.order.toss.TossPaymentCancelRequest;
import com.kitschcatch.backend.domain.order.toss.TossPaymentConfirmRequest;
import com.kitschcatch.backend.domain.order.toss.TossPaymentResponse;
import com.kitschcatch.backend.domain.order.toss.TossPaymentsClient;
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
	private TossPaymentsClient tossPaymentsClient;
	private PostRepository postRepository;
	private UserRepository userRepository;
	private OrderService orderService;
	private PaymentTransactionService paymentTransactionService;
	private PaymentService paymentService;
	private User buyer;
	private User seller;
	private Post post;

	@BeforeEach
	void setUp() {
		orderRepository = mock(PurchaseOrderRepository.class);
		paymentRepository = mock(PaymentRepository.class);
		tossPaymentsClient = mock(TossPaymentsClient.class);
		postRepository = mock(PostRepository.class);
		userRepository = mock(UserRepository.class);
		orderService = new OrderService(orderRepository, paymentRepository, postRepository, userRepository);
		paymentTransactionService = new PaymentTransactionService(paymentRepository, orderRepository);
		paymentService = new PaymentService(paymentTransactionService, tossPaymentsClient);

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
		assertThat(response.orderId()).hasSize(36);
		assertThat(response.paymentId()).startsWith("PAY-");
		assertThat(response.paymentId()).hasSize(36);
		assertThat(response.status()).isEqualTo(PaymentStatus.READY);
	}

	@Test
	@DisplayName("주문 생성은 판매 중이 아닌 게시글이면 예외를 던진다")
	void createOrderWithNotOnSalePostThrowsException() {
		Post reservedPost = post(10L, seller, 650000L, ProductStatus.RESERVED);
		when(userRepository.findById(1L)).thenReturn(Optional.of(buyer));
		when(postRepository.findByIdAndDeletedAtIsNull(10L)).thenReturn(Optional.of(reservedPost));

		assertThatThrownBy(() -> orderService.createOrder(
			1L,
			new CreateOrderRequest(10L, 650000L, PaymentMethod.CARD)
		))
			.isInstanceOf(BusinessException.class)
			.extracting("errorCode")
			.isEqualTo(ErrorCode.BAD_REQUEST);
		verifyNoInteractions(orderRepository, paymentRepository);
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
		assertThat(response.paymentId()).hasSize(36);
		assertThat(response.status()).isEqualTo(PaymentStatus.READY);
	}

	@Test
	@DisplayName("결제 승인은 결제를 성공으로 바꾸고 주문을 결제 완료로 바꾼다")
	void confirmPaymentMarksPaymentAndOrderPaid() {
		PurchaseOrder order = order("ORD-123", buyer, post, OrderStatus.PENDING);
		Payment payment = payment("PAY-999", order, PaymentStatus.READY);
		when(paymentRepository.findByPaymentIdAndOrderUserIdWithLock("PAY-999", 1L)).thenReturn(Optional.of(payment));
		when(tossPaymentsClient.confirm(new TossPaymentConfirmRequest("toss-payment-key", "ORD-123", 650000L)))
			.thenReturn(new TossPaymentResponse("toss-payment-key", "ORD-123", 650000L, "DONE"));

		PaymentResponse response = paymentService.confirmPayment(
			1L,
			"PAY-999",
			new ConfirmPaymentRequest("PAY-999", "toss-payment-key")
		);

		assertThat(response.status()).isEqualTo(PaymentStatus.SUCCESS);
		assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.PAID);
		assertThat(order.getPgPaymentKey()).isEqualTo("toss-payment-key");
		verify(tossPaymentsClient).confirm(new TossPaymentConfirmRequest("toss-payment-key", "ORD-123", 650000L));
		verify(paymentRepository, times(2)).findByPaymentIdAndOrderUserIdWithLock("PAY-999", 1L);
	}

	@Test
	@DisplayName("결제 승인은 주문이 대기 상태가 아니면 토스 승인 요청을 보내지 않는다")
	void confirmPaymentWithNotPendingOrderThrowsException() {
		PurchaseOrder order = order("ORD-123", buyer, post, OrderStatus.PAID);
		Payment payment = payment("PAY-999", order, PaymentStatus.READY);
		when(paymentRepository.findByPaymentIdAndOrderUserIdWithLock("PAY-999", 1L)).thenReturn(Optional.of(payment));

		assertThatThrownBy(() -> paymentService.confirmPayment(
			1L,
			"PAY-999",
			new ConfirmPaymentRequest("PAY-999", "toss-payment-key")
		))
			.isInstanceOf(BusinessException.class)
			.extracting("errorCode")
			.isEqualTo(ErrorCode.PAYMENT_INVALID_STATUS);
		verifyNoInteractions(tossPaymentsClient);
	}

	@Test
	@DisplayName("결제 승인은 토스 응답 상태가 DONE이 아니면 대기 상태로 되돌린다")
	void confirmPaymentWithUnexpectedTossStatusRestoresReadyStatus() {
		PurchaseOrder order = order("ORD-123", buyer, post, OrderStatus.PENDING);
		Payment payment = payment("PAY-999", order, PaymentStatus.READY);
		when(paymentRepository.findByPaymentIdAndOrderUserIdWithLock("PAY-999", 1L)).thenReturn(Optional.of(payment));
		when(tossPaymentsClient.confirm(new TossPaymentConfirmRequest("toss-payment-key", "ORD-123", 650000L)))
			.thenReturn(new TossPaymentResponse("toss-payment-key", "ORD-123", 650000L, "READY"));

		assertThatThrownBy(() -> paymentService.confirmPayment(
			1L,
			"PAY-999",
			new ConfirmPaymentRequest("PAY-999", "toss-payment-key")
		))
			.isInstanceOf(BusinessException.class)
			.extracting("errorCode")
			.isEqualTo(ErrorCode.TOSS_PAYMENTS_REQUEST_FAILED);
		assertThat(payment.getPaymentStatus()).isEqualTo(PaymentStatus.READY);
	}

	@Test
	@DisplayName("결제 취소는 결제를 취소로 바꾸고 주문을 취소로 바꾼다")
	void cancelPaymentMarksPaymentAndOrderCanceled() {
		PurchaseOrder order = order("ORD-123", buyer, post, OrderStatus.PAID);
		Payment payment = payment("PAY-999", order, PaymentStatus.SUCCESS, "toss-payment-key");
		when(paymentRepository.findByPaymentIdAndOrderUserIdWithLock("PAY-999", 1L)).thenReturn(Optional.of(payment));
		when(tossPaymentsClient.cancel(new TossPaymentCancelRequest("toss-payment-key", "고객 요청")))
			.thenReturn(new TossPaymentResponse("toss-payment-key", "ORD-123", 650000L, "CANCELED"));

		PaymentResponse response = paymentService.cancelPayment(1L, "PAY-999");

		assertThat(response.status()).isEqualTo(PaymentStatus.CANCELED);
		assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.CANCELED);
		verify(tossPaymentsClient).cancel(new TossPaymentCancelRequest("toss-payment-key", "고객 요청"));
		verify(paymentRepository, times(2)).findByPaymentIdAndOrderUserIdWithLock("PAY-999", 1L);
	}

	@Test
	@DisplayName("결제 취소는 토스 응답 상태가 CANCELED가 아니면 성공 상태로 되돌린다")
	void cancelPaymentWithUnexpectedTossStatusRestoresSuccessStatus() {
		PurchaseOrder order = order("ORD-123", buyer, post, OrderStatus.PAID);
		Payment payment = payment("PAY-999", order, PaymentStatus.SUCCESS, "toss-payment-key");
		when(paymentRepository.findByPaymentIdAndOrderUserIdWithLock("PAY-999", 1L)).thenReturn(Optional.of(payment));
		when(tossPaymentsClient.cancel(new TossPaymentCancelRequest("toss-payment-key", "고객 요청")))
			.thenReturn(new TossPaymentResponse("toss-payment-key", "ORD-123", 650000L, "DONE"));

		assertThatThrownBy(() -> paymentService.cancelPayment(1L, "PAY-999"))
			.isInstanceOf(BusinessException.class)
			.extracting("errorCode")
			.isEqualTo(ErrorCode.TOSS_PAYMENTS_REQUEST_FAILED);
		assertThat(payment.getPaymentStatus()).isEqualTo(PaymentStatus.SUCCESS);
		assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.PAID);
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
		return post(id, seller, price, ProductStatus.ON_SALE);
	}

	private Post post(Long id, User seller, Long price, ProductStatus productStatus) {
		Post post = Post.builder()
			.user(seller)
			.title("피규어 판매")
			.description("미개봉 상품")
			.price(price)
			.productCategory(ProductCategory.GOODS)
			.productCondition(ProductCondition.NEW)
			.productStatus(productStatus)
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
		return payment(paymentId, order, paymentStatus, null);
	}

	private Payment payment(String paymentId, PurchaseOrder order, PaymentStatus paymentStatus, String paymentKey) {
		Payment payment = Payment.builder()
			.paymentId(paymentId)
			.order(order)
			.amount(order.getAmount())
			.paymentMethod(PaymentMethod.CARD)
			.paymentStatus(paymentStatus)
			.paymentKey(paymentKey)
			.build();
		ReflectionTestUtils.setField(payment, "createdAt", LocalDateTime.of(2026, 4, 13, 15, 0));
		return payment;
	}
}
