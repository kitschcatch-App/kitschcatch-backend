// PG 결과 복구 서비스의 승인·취소 상태 반영을 검증하는 테스트
package com.kitschcatch.backend.domain.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.kitschcatch.backend.domain.order.dto.PaymentResponse;
import com.kitschcatch.backend.domain.order.entity.OrderStatus;
import com.kitschcatch.backend.domain.order.entity.Payment;
import com.kitschcatch.backend.domain.order.entity.PaymentAttempt;
import com.kitschcatch.backend.domain.order.entity.PaymentAttemptOperation;
import com.kitschcatch.backend.domain.order.entity.PaymentAttemptStatus;
import com.kitschcatch.backend.domain.order.entity.PaymentMethod;
import com.kitschcatch.backend.domain.order.entity.PaymentStatus;
import com.kitschcatch.backend.domain.order.entity.PgProvider;
import com.kitschcatch.backend.domain.order.entity.PurchaseOrder;
import com.kitschcatch.backend.domain.order.repository.PaymentAttemptRepository;
import com.kitschcatch.backend.domain.order.repository.PaymentRepository;
import com.kitschcatch.backend.domain.order.toss.TossPaymentResponse;
import com.kitschcatch.backend.domain.post.entity.Post;
import com.kitschcatch.backend.domain.post.entity.ProductCategory;
import com.kitschcatch.backend.domain.post.entity.ProductCondition;
import com.kitschcatch.backend.domain.post.entity.ProductStatus;
import com.kitschcatch.backend.domain.user.entity.AuthProvider;
import com.kitschcatch.backend.domain.user.entity.User;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PaymentRecoveryServiceTest {

	private PaymentAttemptRepository attemptRepository;
	private PaymentRepository paymentRepository;
	private OrderReservationService reservationService;
	private PaymentRecoveryService recoveryService;
	private PurchaseOrder order;
	private Payment payment;

	@BeforeEach
	void setUp() {
		attemptRepository = mock(PaymentAttemptRepository.class);
		paymentRepository = mock(PaymentRepository.class);
		reservationService = mock(OrderReservationService.class);
		recoveryService = new PaymentRecoveryService(attemptRepository, paymentRepository, reservationService);

		User seller = user(2L, "seller");
		User buyer = user(1L, "buyer");
		Post post = Post.builder()
			.user(seller)
			.title("키링")
			.description("상품")
			.price(12000L)
			.productCategory(ProductCategory.GOODS)
			.productCondition(ProductCondition.NEW)
			.productStatus(ProductStatus.ON_SALE)
			.build();
		setId(post, 10L);
		order = PurchaseOrder.builder()
			.orderNumber("ORD-1")
			.user(buyer)
			.post(post)
			.amount(12000L)
			.pgProvider(PgProvider.TOSS_PAYMENTS)
			.orderStatus(OrderStatus.PENDING)
			.reservationExpiresAt(LocalDateTime.now().plusMinutes(15))
			.build();
		setId(order, 11L);
		post.reserve(order.getOrderNumber());
		payment = Payment.builder()
			.paymentId("PAY-1")
			.order(order)
			.amount(12000L)
			.paymentMethod(PaymentMethod.CARD)
			.paymentStatus(PaymentStatus.PROCESSING)
			.paymentKey("key-1")
			.build();
	}

	@Test
	@DisplayName("승인 완료 조회는 결제·주문·상품을 성공 상태로 복구한다")
	void recoversSuccessfulConfirmation() {
		PaymentAttempt attempt = attempt(PaymentAttemptOperation.CONFIRM, PaymentAttemptStatus.UNKNOWN, "key-1");
		stub(attempt);

		PaymentResponse response = recoveryService.recover(
			"ATT-1",
			new TossPaymentResponse("key-1", "ORD-1", 12000L, "DONE", 0L,
				"2026-09-16T10:15:30+09:00", null, "tx-1")
		);

		assertThat(response.status()).isEqualTo(PaymentStatus.SUCCESS);
		assertThat(payment.getPaymentStatus()).isEqualTo(PaymentStatus.SUCCESS);
		assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.PAID);
		assertThat(order.getPost().getProductStatus()).isEqualTo(ProductStatus.SOLD_OUT);
		assertThat(attempt.getAttemptStatus()).isEqualTo(PaymentAttemptStatus.SUCCEEDED);
	}

	@Test
	@DisplayName("승인 실패 확정 조회는 재시도 가능한 실패 상태로 복구한다")
	void recoversFailedConfirmation() {
		PaymentAttempt attempt = attempt(PaymentAttemptOperation.CONFIRM, PaymentAttemptStatus.UNKNOWN, "key-1");
		stub(attempt);

		PaymentResponse response = recoveryService.recover(
			"ATT-1",
			new TossPaymentResponse("key-1", "ORD-1", 12000L, "ABORTED")
		);

		assertThat(response.status()).isEqualTo(PaymentStatus.FAILED);
		assertThat(payment.getPaymentStatus()).isEqualTo(PaymentStatus.FAILED);
		assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.PENDING);
		assertThat(order.getPost().getProductStatus()).isEqualTo(ProductStatus.RESERVED);
		assertThat(attempt.getAttemptStatus()).isEqualTo(PaymentAttemptStatus.FAILED);
	}

	@Test
	@DisplayName("취소 완료 조회는 주문 예약을 해제한다")
	void recoversSuccessfulCancellation() {
		payment.confirm("key-1");
		PaymentAttempt attempt = attempt(PaymentAttemptOperation.CANCEL, PaymentAttemptStatus.UNKNOWN, "key-1");
		stub(attempt);

		PaymentResponse response = recoveryService.recover(
			"ATT-1",
			new TossPaymentResponse("key-1", "ORD-1", 12000L, "CANCELED", 0L,
				null, "2026-09-16T10:20:30+09:00", "tx-2")
		);

		assertThat(response.status()).isEqualTo(PaymentStatus.CANCELED);
		assertThat(payment.getPaymentStatus()).isEqualTo(PaymentStatus.CANCELED);
		assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.CANCELED);
		assertThat(order.getPost().getProductStatus()).isEqualTo(ProductStatus.ON_SALE);
	}

	@Test
	@DisplayName("식별자나 금액이 다른 PG 응답은 운영 확인 상태로 남긴다")
	void doesNotApplyMismatchedResponse() {
		PaymentAttempt attempt = attempt(PaymentAttemptOperation.CONFIRM, PaymentAttemptStatus.UNKNOWN, "key-1");
		stub(attempt);

		PaymentResponse response = recoveryService.recover(
			"ATT-1",
			new TossPaymentResponse("different-key", "ORD-1", 9999L, "DONE")
		);

		assertThat(response.status()).isEqualTo(PaymentStatus.PROCESSING);
		assertThat(payment.getPaymentStatus()).isEqualTo(PaymentStatus.PROCESSING);
		assertThat(attempt.getAttemptStatus()).isEqualTo(PaymentAttemptStatus.UNKNOWN);
	}

	@Test
	@DisplayName("현재 결제 시도가 아닌 늦은 응답은 상태를 덮어쓰지 않는다")
	void ignoresStaleAttemptResponse() {
		PaymentAttempt attempt = attempt(PaymentAttemptOperation.CONFIRM, PaymentAttemptStatus.UNKNOWN, "key-1");
		payment.bindAttempt("ATT-2", com.kitschcatch.backend.domain.order.entity.PaymentOperation.CONFIRM);
		stub(attempt);

		PaymentResponse response = recoveryService.recover(
			"ATT-1",
			new TossPaymentResponse("key-1", "ORD-1", 12000L, "DONE")
		);

		assertThat(response.status()).isEqualTo(PaymentStatus.PROCESSING);
		assertThat(payment.getPaymentStatus()).isEqualTo(PaymentStatus.PROCESSING);
		assertThat(attempt.getAttemptStatus()).isEqualTo(PaymentAttemptStatus.UNKNOWN);
		assertThat(attempt.getFailureReason()).isEqualTo("오래된 결제 시도의 응답입니다.");
	}

	@Test
	@DisplayName("성공 결제의 외부 전액 취소도 동일한 복구 규칙으로 반영한다")
	void recoversExternalCancellationOfSuccessfulPayment() {
		payment.confirm("key-1");
		PaymentAttempt attempt = attempt(PaymentAttemptOperation.CONFIRM, PaymentAttemptStatus.SUCCEEDED, "key-1");
		payment.bindAttempt("ATT-1", com.kitschcatch.backend.domain.order.entity.PaymentOperation.CONFIRM);
		stub(attempt);

		PaymentResponse response = recoveryService.recover(
			"ATT-1",
			new TossPaymentResponse("key-1", "ORD-1", 12000L, "CANCELED", 0L,
				null, "2026-09-16T10:20:30+09:00", "tx-2")
		);

		assertThat(response.status()).isEqualTo(PaymentStatus.CANCELED);
		assertThat(payment.getPaymentStatus()).isEqualTo(PaymentStatus.CANCELED);
		assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.CANCELED);
		assertThat(order.getPost().getProductStatus()).isEqualTo(ProductStatus.ON_SALE);
	}

	private void stub(PaymentAttempt attempt) {
		when(attemptRepository.findOrderIdByAttemptId("ATT-1")).thenReturn(Optional.of(order.getId()));
		when(attemptRepository.findPaymentIdByAttemptId("ATT-1")).thenReturn(Optional.of("PAY-1"));
		when(attemptRepository.findByAttemptIdForUpdate("ATT-1")).thenReturn(Optional.of(attempt));
		when(paymentRepository.findByPaymentIdForUpdate("PAY-1")).thenReturn(Optional.of(payment));
		when(reservationService.lockOrder(order.getId())).thenReturn(order);
	}

	private PaymentAttempt attempt(PaymentAttemptOperation operation, PaymentAttemptStatus status, String paymentKey) {
		LocalDateTime now = LocalDateTime.now();
		return PaymentAttempt.builder()
			.attemptId("ATT-1")
			.payment(payment)
			.sequenceNumber(1)
			.operation(operation)
			.attemptStatus(status)
			.pgOrderId("ORD-1")
			.paymentKey(paymentKey)
			.amount(12000L)
			.pgIdempotencyKey(operation.name().toLowerCase() + "-1")
			.requestedAt(now)
			.nextCheckAt(now)
			.build();
	}

	private User user(Long id, String nickname) {
		User user = User.builder()
			.nickname(nickname)
			.email(nickname + "@example.com")
			.authProvider(AuthProvider.KAKAO)
			.providerUserId(nickname + "-provider")
			.build();
		setId(user, id);
		return user;
	}

	private void setId(Object target, Long id) {
		org.springframework.test.util.ReflectionTestUtils.setField(target, "id", id);
	}
}
