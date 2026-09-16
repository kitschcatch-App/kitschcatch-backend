// 실패한 결제 시도의 재시도 준비와 재승인 연결을 검증하는 테스트
package com.kitschcatch.backend.domain.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.kitschcatch.backend.domain.order.dto.ConfirmPaymentRequest;
import com.kitschcatch.backend.domain.order.dto.RetryPaymentRequest;
import com.kitschcatch.backend.domain.order.dto.RetryPaymentResponse;
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
import com.kitschcatch.backend.domain.order.repository.PurchaseOrderRepository;
import com.kitschcatch.backend.domain.post.entity.Post;
import com.kitschcatch.backend.domain.post.entity.ProductCategory;
import com.kitschcatch.backend.domain.post.entity.ProductCondition;
import com.kitschcatch.backend.domain.post.entity.ProductStatus;
import com.kitschcatch.backend.domain.user.entity.AuthProvider;
import com.kitschcatch.backend.domain.user.entity.User;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class PaymentRetryServiceTest {

	private PaymentRepository paymentRepository;
	private PaymentAttemptRepository attemptRepository;
	private PaymentTransactionService transactionService;
	private OrderReservationService reservationService;
	private PurchaseOrder order;
	private Payment payment;
	private PaymentAttempt failedAttempt;

	@BeforeEach
	void setUp() {
		paymentRepository = mock(PaymentRepository.class);
		attemptRepository = mock(PaymentAttemptRepository.class);
		PurchaseOrderRepository orderRepository = mock(PurchaseOrderRepository.class);
		reservationService = mock(OrderReservationService.class);
		transactionService = new PaymentTransactionService(paymentRepository, orderRepository, reservationService, attemptRepository);

		User seller = user(2L, "seller");
		User buyer = user(1L, "buyer");
		Post post = Post.builder().user(seller).title("키링").description("상품").price(12000L)
			.productCategory(ProductCategory.GOODS).productCondition(ProductCondition.NEW)
			.productStatus(ProductStatus.ON_SALE).build();
		ReflectionTestUtils.setField(post, "id", 10L);
		order = PurchaseOrder.builder().orderNumber("ORD-1").user(buyer).post(post).amount(12000L)
			.pgProvider(PgProvider.TOSS_PAYMENTS).orderStatus(OrderStatus.PENDING)
			.reservationExpiresAt(LocalDateTime.now().plusMinutes(10)).build();
		ReflectionTestUtils.setField(order, "id", 11L);
		post.reserve(order.getOrderNumber());
		payment = Payment.builder().paymentId("PAY-1").order(order).amount(12000L)
			.paymentMethod(PaymentMethod.CARD).paymentStatus(PaymentStatus.FAILED).build();
		ReflectionTestUtils.setField(payment, "id", 12L);
		failedAttempt = attempt("ATT-FAILED", 1, PaymentAttemptStatus.FAILED, null);
		stubPaymentLock();
		when(attemptRepository.findByAttemptIdForUpdate("ATT-FAILED")).thenReturn(Optional.of(failedAttempt));
		when(attemptRepository.findByPaymentIdAndSourceAttemptAttemptId(12L, "ATT-FAILED"))
			.thenReturn(Optional.empty());
		when(attemptRepository.findTopByPaymentIdOrderBySequenceNumberDesc(12L))
			.thenReturn(Optional.of(failedAttempt));
		when(attemptRepository.save(any(PaymentAttempt.class))).thenAnswer(invocation -> invocation.getArgument(0));
	}

	@Test
	void preparesNewAttemptForFailedPayment() {
		RetryPaymentResponse response = transactionService.prepareRetry(
			1L, "PAY-1", new RetryPaymentRequest("ATT-FAILED")
		);

		assertThat(response.paymentId()).isEqualTo("PAY-1");
		assertThat(response.status()).isEqualTo(PaymentStatus.READY);
		assertThat(response.attemptStatus()).isEqualTo(PaymentAttemptStatus.PREPARED);
		assertThat(response.pgOrderId()).startsWith("PG-");
		assertThat(payment.getCurrentAttemptId()).isEqualTo(response.attemptId());
	}

	@Test
	void retryAttemptUsesIssuedPgOrderIdWhenConfirming() {
		RetryPaymentResponse response = transactionService.prepareRetry(
			1L, "PAY-1", new RetryPaymentRequest("ATT-FAILED")
		);
		PaymentAttempt retryAttempt = attempt(response.attemptId(), 2, PaymentAttemptStatus.PREPARED, response.pgOrderId());
		when(attemptRepository.findByAttemptIdForUpdate(response.attemptId())).thenReturn(Optional.of(retryAttempt));

		PaymentOperationContext context = transactionService.startConfirm(
			1L, "PAY-1", new ConfirmPaymentRequest("PAY-1", "new-key", response.attemptId())
		);

		assertThat(context.attemptId()).isEqualTo(response.attemptId());
		assertThat(context.pgOrderId()).isEqualTo(response.pgOrderId());
		assertThat(retryAttempt.getAttemptStatus()).isEqualTo(PaymentAttemptStatus.PROCESSING);
		assertThat(retryAttempt.getPaymentKey()).isEqualTo("new-key");
	}

	private void stubPaymentLock() {
		when(paymentRepository.findOrderIdByPaymentIdAndOrderUserId("PAY-1", 1L)).thenReturn(Optional.of(11L));
		when(reservationService.lockOrder(11L)).thenReturn(order);
		when(paymentRepository.findByPaymentIdAndOrderUserIdWithLock("PAY-1", 1L)).thenReturn(Optional.of(payment));
	}

	private PaymentAttempt attempt(String attemptId, int sequence, PaymentAttemptStatus status, String pgOrderId) {
		return PaymentAttempt.builder().attemptId(attemptId).payment(payment).sequenceNumber(sequence)
			.operation(PaymentAttemptOperation.CONFIRM).attemptStatus(status)
			.pgOrderId(pgOrderId == null ? "ORD-1" : pgOrderId).amount(12000L)
			.pgIdempotencyKey("confirm-" + attemptId).requestedAt(LocalDateTime.now())
			.nextCheckAt(LocalDateTime.now()).build();
	}

	private User user(Long id, String nickname) {
		User user = User.builder().nickname(nickname).email(nickname + "@example.com")
			.authProvider(AuthProvider.KAKAO).providerUserId(nickname + "-provider").build();
		ReflectionTestUtils.setField(user, "id", id);
		return user;
	}
}
