package com.kitschcatch.backend.domain.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.kitschcatch.backend.domain.order.entity.OrderStatus;
import com.kitschcatch.backend.domain.order.entity.PgProvider;
import com.kitschcatch.backend.domain.order.entity.PurchaseOrder;
import com.kitschcatch.backend.domain.payment.client.TossPaymentClient;
import com.kitschcatch.backend.domain.payment.client.TossPaymentException;
import com.kitschcatch.backend.domain.payment.client.TossPaymentProperties;
import com.kitschcatch.backend.domain.payment.client.TossPaymentResult;
import com.kitschcatch.backend.domain.payment.dto.CancelPaymentRequest;
import com.kitschcatch.backend.domain.payment.dto.ConfirmPaymentRequest;
import com.kitschcatch.backend.domain.payment.dto.CreatePaymentRequest;
import com.kitschcatch.backend.domain.payment.dto.PaymentResponse;
import com.kitschcatch.backend.domain.payment.entity.Payment;
import com.kitschcatch.backend.domain.payment.entity.PaymentMethod;
import com.kitschcatch.backend.domain.payment.entity.PaymentStatus;
import com.kitschcatch.backend.domain.payment.repository.PaymentRepository;
import com.kitschcatch.backend.domain.post.entity.Post;
import com.kitschcatch.backend.domain.post.entity.ProductCategory;
import com.kitschcatch.backend.domain.post.entity.ProductCondition;
import com.kitschcatch.backend.domain.post.entity.ProductStatus;
import com.kitschcatch.backend.domain.user.entity.AuthProvider;
import com.kitschcatch.backend.domain.user.entity.User;
import com.kitschcatch.backend.global.exception.BusinessException;
import com.kitschcatch.backend.global.exception.ErrorCode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionOperations;

class PaymentServiceTest {

	private PaymentRepository paymentRepository;
	private com.kitschcatch.backend.domain.order.repository.PurchaseOrderRepository orderRepository;
	private TossPaymentClient tossPaymentClient;
	private TossPaymentProperties tossPaymentProperties;
	private PaymentService paymentService;
	private TrackingTransactionOperations transactionOperations;
	private User buyer;
	private User seller;
	private Post post;
	private PurchaseOrder order;

	@BeforeEach
	void setUp() {
		paymentRepository = mock(PaymentRepository.class);
		orderRepository = mock(com.kitschcatch.backend.domain.order.repository.PurchaseOrderRepository.class);
		tossPaymentClient = mock(TossPaymentClient.class);
		tossPaymentProperties = new TossPaymentProperties(
			"test_sk",
			"test_ck",
			"",
			"https://example.com/payments/success",
			"https://example.com/payments/fail",
			null,
			null
		);
		transactionOperations = new TrackingTransactionOperations();
		Clock clock = Clock.fixed(Instant.parse("2026-05-21T05:00:00Z"), ZoneId.of("Asia/Seoul"));
		paymentService = new PaymentService(
			paymentRepository,
			orderRepository,
			tossPaymentClient,
			tossPaymentProperties,
			clock,
			transactionOperations
		);
		buyer = user(2L, "buyer");
		seller = user(1L, "seller");
		post = post(ProductStatus.RESERVED);
		order = pendingOrder();
		ReflectionTestUtils.setField(order, "id", 30L);
	}

	@Test
	@DisplayName("결제 생성은 내부 결제를 만들고 토스 SDK 요청 값을 반환한다")
	void createPaymentReturnsTossSdkRequestValues() {
		AtomicReference<Payment> savedPayment = new AtomicReference<>();
		when(orderRepository.findByIdAndUserIdForUpdate(30L, 2L)).thenReturn(Optional.of(order));
		when(paymentRepository.save(any(Payment.class))).thenAnswer(invocation -> {
			Payment payment = invocation.getArgument(0);
			ReflectionTestUtils.setField(payment, "id", 40L);
			savedPayment.set(payment);
			return payment;
		});

		PaymentResponse response = paymentService.createPayment(2L, new CreatePaymentRequest(30L, PaymentMethod.CARD));

		verifyNoInteractions(tossPaymentClient);
		assertThat(response.paymentId()).isEqualTo(40L);
		assertThat(response.status()).isEqualTo(PaymentStatus.REQUESTED);
		assertThat(response.pgOrderId()).startsWith("KC-PAY-");
		assertThat(response.clientKey()).isEqualTo("test_ck");
		assertThat(response.orderName()).isEqualTo("키링");
		assertThat(response.successUrl()).isEqualTo("https://example.com/payments/success?paymentId=40");
		assertThat(response.failUrl()).isEqualTo("https://example.com/payments/fail?paymentId=40");
	}

	@Test
	@DisplayName("같은 주문에 진행 중인 결제가 있으면 새 결제를 만들지 않고 기존 결제를 반환한다")
	void createPaymentReturnsExistingRequestedPayment() {
		Payment payment = requestedPayment();
		when(orderRepository.findByIdAndUserIdForUpdate(30L, 2L)).thenReturn(Optional.of(order));
		when(paymentRepository.findFirstByOrderIdAndStatusInOrderByCreatedAtDesc(
			eq(30L),
			any()
		)).thenReturn(Optional.of(payment));

		PaymentResponse response = paymentService.createPayment(2L, new CreatePaymentRequest(30L, PaymentMethod.CARD));

		verify(paymentRepository, never()).save(any(Payment.class));
		verifyNoInteractions(tossPaymentClient);
		assertThat(response.paymentId()).isEqualTo(40L);
		assertThat(response.status()).isEqualTo(PaymentStatus.REQUESTED);
	}

	@Test
	@DisplayName("READY 결제가 남으면 토스 호출 없이 SDK 요청 가능 상태로 되돌린다")
	void createPaymentRetriesReadyPayment() {
		Payment payment = readyPayment();
		when(orderRepository.findByIdAndUserIdForUpdate(30L, 2L)).thenReturn(Optional.of(order));
		when(paymentRepository.findFirstByOrderIdAndStatusInOrderByCreatedAtDesc(
			eq(30L),
			any()
		)).thenReturn(Optional.of(payment));

		PaymentResponse response = paymentService.createPayment(2L, new CreatePaymentRequest(30L, PaymentMethod.CARD));

		verify(paymentRepository, never()).save(any(Payment.class));
		verifyNoInteractions(tossPaymentClient);
		assertThat(response.status()).isEqualTo(PaymentStatus.REQUESTED);
		assertThat(payment.getStatus()).isEqualTo(PaymentStatus.REQUESTED);
	}

	@Test
	@DisplayName("만료된 주문에 진행 중인 결제가 있으면 결제를 실패 처리한다")
	void createPaymentFailsExistingPaymentWhenOrderExpired() {
		Payment payment = requestedPayment();
		ReflectionTestUtils.setField(order, "expiresAt", LocalDateTime.of(2026, 5, 21, 13, 59));
		when(orderRepository.findByIdAndUserIdForUpdate(30L, 2L)).thenReturn(Optional.of(order));
		when(paymentRepository.findFirstByOrderIdAndStatusInOrderByCreatedAtDesc(
			eq(30L),
			any()
		)).thenReturn(Optional.of(payment));

		assertThatThrownBy(() -> paymentService.createPayment(2L, new CreatePaymentRequest(30L, PaymentMethod.CARD)))
			.isInstanceOf(BusinessException.class)
			.extracting("errorCode")
			.isEqualTo(ErrorCode.ORDER_EXPIRED);

		assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
		assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.EXPIRED);
		assertThat(post.getProductStatus()).isEqualTo(ProductStatus.ON_SALE);
	}

	@Test
	@DisplayName("웹훅이 필요한 가상계좌 결제 생성은 거부한다")
	void createPaymentRejectsVirtualAccount() {
		when(orderRepository.findByIdAndUserIdForUpdate(30L, 2L)).thenReturn(Optional.of(order));

		assertThatThrownBy(() -> paymentService.createPayment(
			2L,
			new CreatePaymentRequest(30L, PaymentMethod.VIRTUAL_ACCOUNT)
		))
			.isInstanceOf(BusinessException.class)
			.extracting("errorCode")
			.isEqualTo(ErrorCode.INVALID_INPUT_VALUE);

		verify(paymentRepository, never()).save(any(Payment.class));
		verifyNoInteractions(tossPaymentClient);
	}

	@Test
	@DisplayName("결제 승인은 토스 응답을 검증하고 주문과 상품을 완료 처리한다")
	void confirmPaymentApprovesOrderAndPost() {
		Payment payment = requestedPayment();
		when(paymentRepository.findByIdAndOrderUserIdForUpdate(40L, 2L)).thenReturn(Optional.of(payment));
		when(tossPaymentClient.confirmPayment("pg-key", "KC-PAY-40", 12000L, "payment-confirm-40"))
			.thenAnswer(invocation -> {
				assertThat(transactionOperations.isInTransaction()).isFalse();
				assertThat(payment.getStatus()).isEqualTo(PaymentStatus.CONFIRMING);
				assertThat(payment.getPgPaymentKey()).isEqualTo("pg-key");
				return new TossPaymentResult("pg-key", "KC-PAY-40", 12000L, "DONE", "tx-key");
			});

		PaymentResponse response = paymentService.confirmPayment(2L, 40L, new ConfirmPaymentRequest("pg-key"));

		assertThat(response.status()).isEqualTo(PaymentStatus.APPROVED);
		assertThat(payment.getPgPaymentKey()).isEqualTo("pg-key");
		assertThat(payment.getPgTransactionId()).isEqualTo("tx-key");
		assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.PAID);
		assertThat(post.getProductStatus()).isEqualTo(ProductStatus.SOLD_OUT);
	}

	@Test
	@DisplayName("이미 승인 보정 중인 결제는 같은 paymentKey로 다시 승인할 수 있다")
	void confirmPaymentRetriesConfirmingPayment() {
		Payment payment = requestedPayment();
		payment.startConfirm("pg-key");
		when(paymentRepository.findByIdAndOrderUserIdForUpdate(40L, 2L)).thenReturn(Optional.of(payment));
		when(tossPaymentClient.confirmPayment("pg-key", "KC-PAY-40", 12000L, "payment-confirm-40"))
			.thenReturn(new TossPaymentResult("pg-key", "KC-PAY-40", 12000L, "DONE", "tx-key"));

		PaymentResponse response = paymentService.confirmPayment(2L, 40L, new ConfirmPaymentRequest("pg-key"));

		assertThat(response.status()).isEqualTo(PaymentStatus.APPROVED);
		assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.PAID);
	}

	@Test
	@DisplayName("토스 승인 응답을 받지 못하면 결제 조회로 승인 성공을 대사한다")
	void confirmPaymentReconcilesDonePaymentWhenTossConfirmFails() {
		Payment payment = requestedPayment();
		when(paymentRepository.findByIdAndOrderUserIdForUpdate(40L, 2L)).thenReturn(Optional.of(payment));
		when(tossPaymentClient.confirmPayment("pg-key", "KC-PAY-40", 12000L, "payment-confirm-40"))
			.thenThrow(new TossPaymentException("토스 승인 실패"));
		when(tossPaymentClient.getPayment("pg-key"))
			.thenReturn(new TossPaymentResult("pg-key", "KC-PAY-40", 12000L, "DONE", "tx-key"));

		PaymentResponse response = paymentService.confirmPayment(2L, 40L, new ConfirmPaymentRequest("pg-key"));

		assertThat(response.status()).isEqualTo(PaymentStatus.APPROVED);
		assertThat(payment.getStatus()).isEqualTo(PaymentStatus.APPROVED);
		assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.PAID);
		assertThat(post.getProductStatus()).isEqualTo(ProductStatus.SOLD_OUT);
	}

	@Test
	@DisplayName("토스 승인과 조회가 모두 불확실하면 paymentKey를 보존하고 확인 중 상태를 유지한다")
	void confirmPaymentKeepsConfirmingWhenTossConfirmAndQueryFail() {
		Payment payment = requestedPayment();
		when(paymentRepository.findByIdAndOrderUserIdForUpdate(40L, 2L)).thenReturn(Optional.of(payment));
		when(tossPaymentClient.confirmPayment("pg-key", "KC-PAY-40", 12000L, "payment-confirm-40"))
			.thenThrow(new TossPaymentException("토스 승인 타임아웃"));
		when(tossPaymentClient.getPayment("pg-key"))
			.thenThrow(new TossPaymentException("토스 결제 조회 타임아웃"));

		assertThatThrownBy(() -> paymentService.confirmPayment(2L, 40L, new ConfirmPaymentRequest("pg-key")))
			.isInstanceOf(BusinessException.class)
			.extracting("errorCode")
			.isEqualTo(ErrorCode.PAYMENT_PROVIDER_FAILED);

		assertThat(payment.getStatus()).isEqualTo(PaymentStatus.CONFIRMING);
		assertThat(payment.getPgPaymentKey()).isEqualTo("pg-key");
	}

	@Test
	@DisplayName("토스 승인 요청이 명확히 잘못됐으면 다시 승인 요청 가능 상태로 되돌린다")
	void confirmPaymentRestoresRequestedWhenTossConfirmClientErrorFails() {
		Payment payment = requestedPayment();
		when(paymentRepository.findByIdAndOrderUserIdForUpdate(40L, 2L)).thenReturn(Optional.of(payment));
		when(tossPaymentClient.confirmPayment("pg-key", "KC-PAY-40", 12000L, "payment-confirm-40"))
			.thenThrow(new TossPaymentException("토스 승인 거부", 400));
		when(tossPaymentClient.getPayment("pg-key"))
			.thenThrow(new TossPaymentException("토스 결제 없음", 404));

		assertThatThrownBy(() -> paymentService.confirmPayment(2L, 40L, new ConfirmPaymentRequest("pg-key")))
			.isInstanceOf(BusinessException.class)
			.extracting("errorCode")
			.isEqualTo(ErrorCode.PAYMENT_PROVIDER_FAILED);

		assertThat(payment.getStatus()).isEqualTo(PaymentStatus.REQUESTED);
		assertThat(payment.getPgPaymentKey()).isNull();
	}

	@Test
	@DisplayName("만료된 주문의 결제 승인 요청은 결제를 실패 처리한다")
	void confirmPaymentFailsExpiredRequestedPayment() {
		Payment payment = requestedPayment();
		ReflectionTestUtils.setField(order, "expiresAt", LocalDateTime.of(2026, 5, 21, 13, 59));
		when(paymentRepository.findByIdAndOrderUserIdForUpdate(40L, 2L)).thenReturn(Optional.of(payment));

		assertThatThrownBy(() -> paymentService.confirmPayment(2L, 40L, new ConfirmPaymentRequest("pg-key")))
			.isInstanceOf(BusinessException.class)
			.extracting("errorCode")
			.isEqualTo(ErrorCode.ORDER_EXPIRED);

		assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
		assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.EXPIRED);
		assertThat(post.getProductStatus()).isEqualTo(ProductStatus.ON_SALE);
	}

	@Test
	@DisplayName("만료된 결제 조회는 주문을 만료하고 결제를 실패 처리한다")
	void getPaymentExpiresRequestedPayment() {
		Payment payment = requestedPayment();
		ReflectionTestUtils.setField(order, "expiresAt", LocalDateTime.of(2026, 5, 21, 13, 59));
		when(paymentRepository.findByIdAndOrderUserIdForUpdate(40L, 2L)).thenReturn(Optional.of(payment));

		PaymentResponse response = paymentService.getPayment(2L, 40L);

		assertThat(response.status()).isEqualTo(PaymentStatus.FAILED);
		assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.EXPIRED);
		assertThat(post.getProductStatus()).isEqualTo(ProductStatus.ON_SALE);
	}

	@Test
	@DisplayName("이미 승인된 결제에 같은 승인 요청이 반복되면 토스 호출 없이 현재 상태를 반환한다")
	void confirmPaymentIsIdempotentWhenAlreadyApproved() {
		Payment payment = requestedPayment();
		payment.approve("pg-key", "tx-key");
		order.markPaid();
		post.markSoldOut();
		when(paymentRepository.findByIdAndOrderUserIdForUpdate(40L, 2L)).thenReturn(Optional.of(payment));

		PaymentResponse response = paymentService.confirmPayment(2L, 40L, new ConfirmPaymentRequest("pg-key"));

		verify(tossPaymentClient, never()).confirmPayment(any(), any(), any(Long.class), any());
		assertThat(response.status()).isEqualTo(PaymentStatus.APPROVED);
	}

	@Test
	@DisplayName("토스 승인 금액이 내부 결제 금액과 다르면 결제를 거부한다")
	void confirmPaymentRejectsAmountMismatch() {
		Payment payment = requestedPayment();
		when(paymentRepository.findByIdAndOrderUserIdForUpdate(40L, 2L)).thenReturn(Optional.of(payment));
		when(tossPaymentClient.confirmPayment("pg-key", "KC-PAY-40", 12000L, "payment-confirm-40"))
			.thenReturn(new TossPaymentResult("pg-key", "KC-PAY-40", 13000L, "DONE", "tx-key"));

		assertThatThrownBy(() -> paymentService.confirmPayment(2L, 40L, new ConfirmPaymentRequest("pg-key")))
			.isInstanceOf(BusinessException.class)
			.extracting("errorCode")
			.isEqualTo(ErrorCode.PAYMENT_AMOUNT_MISMATCH);
	}

	@Test
	@DisplayName("결제 취소는 토스 전액 취소 후 주문과 상품을 되돌린다")
	void cancelPaymentCancelsApprovedPayment() {
		Payment payment = requestedPayment();
		payment.approve("pg-key", "tx-key");
		order.markPaid();
		post.markSoldOut();
		when(paymentRepository.findByIdAndOrderUserIdForUpdate(40L, 2L)).thenReturn(Optional.of(payment));
		when(tossPaymentClient.cancelPayment("pg-key", "단순 변심", "payment-cancel-40"))
			.thenAnswer(invocation -> {
				assertThat(transactionOperations.isInTransaction()).isFalse();
				assertThat(payment.getStatus()).isEqualTo(PaymentStatus.CANCELING);
				return new TossPaymentResult("pg-key", "KC-PAY-40", 12000L, "CANCELED", "cancel-tx-key");
			});

		PaymentResponse response = paymentService.cancelPayment(2L, 40L, new CancelPaymentRequest("단순 변심"));

		assertThat(response.status()).isEqualTo(PaymentStatus.CANCELED);
		assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.CANCELED);
		assertThat(post.getProductStatus()).isEqualTo(ProductStatus.ON_SALE);
	}

	@Test
	@DisplayName("토스 취소 응답을 받지 못하면 결제 조회로 취소 성공을 대사한다")
	void cancelPaymentReconcilesCanceledPaymentWhenTossCancelFails() {
		Payment payment = requestedPayment();
		payment.approve("pg-key", "tx-key");
		order.markPaid();
		post.markSoldOut();
		when(paymentRepository.findByIdAndOrderUserIdForUpdate(40L, 2L)).thenReturn(Optional.of(payment));
		when(tossPaymentClient.cancelPayment("pg-key", "단순 변심", "payment-cancel-40"))
			.thenThrow(new TossPaymentException("토스 취소 실패"));

		when(tossPaymentClient.getPayment("pg-key"))
			.thenReturn(new TossPaymentResult("pg-key", "KC-PAY-40", 12000L, "CANCELED", "cancel-tx-key"));

		PaymentResponse response = paymentService.cancelPayment(2L, 40L, new CancelPaymentRequest("단순 변심"));

		assertThat(response.status()).isEqualTo(PaymentStatus.CANCELED);
		assertThat(payment.getStatus()).isEqualTo(PaymentStatus.CANCELED);
		assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.CANCELED);
		assertThat(post.getProductStatus()).isEqualTo(ProductStatus.ON_SALE);
	}

	@Test
	@DisplayName("토스 취소와 조회가 모두 불확실하면 취소 중 상태를 유지한다")
	void cancelPaymentKeepsCancelingWhenTossCancelAndQueryFail() {
		Payment payment = requestedPayment();
		payment.approve("pg-key", "tx-key");
		order.markPaid();
		post.markSoldOut();
		when(paymentRepository.findByIdAndOrderUserIdForUpdate(40L, 2L)).thenReturn(Optional.of(payment));
		when(tossPaymentClient.cancelPayment("pg-key", "단순 변심", "payment-cancel-40"))
			.thenThrow(new TossPaymentException("토스 취소 타임아웃"));
		when(tossPaymentClient.getPayment("pg-key"))
			.thenThrow(new TossPaymentException("토스 결제 조회 타임아웃"));

		assertThatThrownBy(() -> paymentService.cancelPayment(2L, 40L, new CancelPaymentRequest("단순 변심")))
			.isInstanceOf(BusinessException.class)
			.extracting("errorCode")
			.isEqualTo(ErrorCode.PAYMENT_PROVIDER_FAILED);

		assertThat(payment.getStatus()).isEqualTo(PaymentStatus.CANCELING);
		assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.PAID);
		assertThat(post.getProductStatus()).isEqualTo(ProductStatus.SOLD_OUT);
	}

	@Test
	@DisplayName("토스 취소 요청이 명확히 잘못됐으면 다시 승인 상태로 되돌린다")
	void cancelPaymentRestoresApprovedWhenTossCancelClientErrorFails() {
		Payment payment = requestedPayment();
		payment.approve("pg-key", "tx-key");
		order.markPaid();
		post.markSoldOut();
		when(paymentRepository.findByIdAndOrderUserIdForUpdate(40L, 2L)).thenReturn(Optional.of(payment));
		when(tossPaymentClient.cancelPayment("pg-key", "단순 변심", "payment-cancel-40"))
			.thenThrow(new TossPaymentException("토스 취소 거부", 400));
		when(tossPaymentClient.getPayment("pg-key"))
			.thenReturn(new TossPaymentResult("pg-key", "KC-PAY-40", 12000L, "DONE", "tx-key"));

		assertThatThrownBy(() -> paymentService.cancelPayment(2L, 40L, new CancelPaymentRequest("단순 변심")))
			.isInstanceOf(BusinessException.class)
			.extracting("errorCode")
			.isEqualTo(ErrorCode.PAYMENT_PROVIDER_FAILED);

		assertThat(payment.getStatus()).isEqualTo(PaymentStatus.APPROVED);
		assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.PAID);
		assertThat(post.getProductStatus()).isEqualTo(ProductStatus.SOLD_OUT);
	}

	private Payment readyPayment() {
		Payment payment = Payment.ready(order, PgProvider.TOSS_PAYMENTS, PaymentMethod.CARD, 12000L);
		ReflectionTestUtils.setField(payment, "id", 40L);
		payment.assignPgOrderId("KC-PAY-40");
		return payment;
	}

	private Payment requestedPayment() {
		Payment payment = readyPayment();
		payment.request();
		return payment;
	}

	private PurchaseOrder pendingOrder() {
		return PurchaseOrder.builder()
			.user(buyer)
			.post(post)
			.amount(12000L)
			.orderStatus(OrderStatus.PENDING)
			.expiresAt(LocalDateTime.of(2026, 5, 21, 14, 15))
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

	private static class TrackingTransactionOperations implements TransactionOperations {

		private boolean inTransaction;

		@Override
		public <T> T execute(org.springframework.transaction.support.TransactionCallback<T> action) {
			inTransaction = true;
			try {
				return action.doInTransaction(new SimpleTransactionStatus());
			} finally {
				inTransaction = false;
			}
		}

		boolean isInTransaction() {
			return inTransaction;
		}
	}
}
