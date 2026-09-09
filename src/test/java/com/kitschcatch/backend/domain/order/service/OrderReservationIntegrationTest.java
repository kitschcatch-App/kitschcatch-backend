// 실제 데이터베이스에서 주문 예약과 결제 및 상품 변경의 동시성을 검증한다.
package com.kitschcatch.backend.domain.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.kitschcatch.backend.domain.order.dto.ConfirmPaymentRequest;
import com.kitschcatch.backend.domain.order.dto.CreateOrderRequest;
import com.kitschcatch.backend.domain.order.dto.CreateOrderResponse;
import com.kitschcatch.backend.domain.order.dto.CreatePaymentRequest;
import com.kitschcatch.backend.domain.order.entity.OrderStatus;
import com.kitschcatch.backend.domain.order.entity.PaymentMethod;
import com.kitschcatch.backend.domain.order.entity.PaymentStatus;
import com.kitschcatch.backend.domain.order.repository.PaymentRepository;
import com.kitschcatch.backend.domain.order.repository.PurchaseOrderRepository;
import com.kitschcatch.backend.domain.order.toss.TossPaymentResponse;
import com.kitschcatch.backend.domain.order.toss.TossPaymentsClient;
import com.kitschcatch.backend.domain.post.dto.UpdatePostRequest;
import com.kitschcatch.backend.domain.post.entity.Post;
import com.kitschcatch.backend.domain.post.entity.ProductCategory;
import com.kitschcatch.backend.domain.post.entity.ProductCondition;
import com.kitschcatch.backend.domain.post.entity.ProductStatus;
import com.kitschcatch.backend.domain.post.repository.PostRepository;
import com.kitschcatch.backend.domain.post.service.PostImageStorage;
import com.kitschcatch.backend.domain.post.service.PostService;
import com.kitschcatch.backend.domain.user.entity.AuthProvider;
import com.kitschcatch.backend.domain.user.entity.User;
import com.kitschcatch.backend.domain.user.repository.UserRepository;
import com.kitschcatch.backend.global.exception.BusinessException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@DataJpaTest(showSql = false, properties = {
	"spring.datasource.url=${ISSUE27_TEST_DB_URL:jdbc:h2:mem:reservation;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000}",
	"spring.datasource.driver-class-name=${ISSUE27_TEST_DB_DRIVER:org.h2.Driver}",
	"spring.datasource.username=${ISSUE27_TEST_DB_USER:sa}",
	"spring.datasource.password=",
	"app.orders.expiration-scan-delay=1d",
	"spring.jpa.hibernate.ddl-auto=create-drop"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({OrderService.class, OrderReservationService.class, OrderReservationScheduler.class,
	PaymentTransactionService.class, PaymentService.class, PostService.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class OrderReservationIntegrationTest {

	@Autowired private OrderService orderService;
	@Autowired private OrderReservationService reservationService;
	@Autowired private OrderReservationScheduler reservationScheduler;
	@Autowired private PaymentTransactionService paymentTransactionService;
	@Autowired private JdbcTemplate jdbc;
	@Autowired private PaymentService paymentService;
	@Autowired private PostService postService;
	@Autowired private PostRepository postRepository;
	@Autowired private PurchaseOrderRepository orderRepository;
	@Autowired private PaymentRepository paymentRepository;
	@Autowired private UserRepository userRepository;
	@Autowired private PlatformTransactionManager transactionManager;
	@MockitoBean private TossPaymentsClient tossPaymentsClient;
	@MockitoBean private PostImageStorage postImageStorage;

	private Long sellerId;
	private Long buyerId;
	private Long postId;


	@BeforeEach
	void setUp() {
		new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
			paymentRepository.deleteAll();
			orderRepository.deleteAll();
			postRepository.deleteAll();
			userRepository.deleteAll();
			userRepository.flush();
			User seller = userRepository.save(user("seller"));
			User buyer = userRepository.save(user("buyer"));
			sellerId = seller.getId();
			buyerId = buyer.getId();
			postId = postRepository.save(Post.builder()
				.user(seller).title("키링").description("미개봉 상품").price(12000L)
				.productCategory(ProductCategory.GOODS).productCondition(ProductCondition.NEW)
				.productStatus(ProductStatus.ON_SALE).build()).getId();
		});
	}

	@Test
	void creatingOrderReservesPostAndRejectsAnotherOrder() {
		createOrder();
		assertThat(postRepository.findById(postId).orElseThrow().getProductStatus()).isEqualTo(ProductStatus.RESERVED);
		assertThatThrownBy(this::createOrder).isInstanceOf(BusinessException.class);
		assertThat(orderRepository.count()).isEqualTo(1);
	}

	@Test
	void simultaneousOrdersHaveExactlyOneWinner() throws Exception {
		List<Long> buyers = new ArrayList<>();
		for (int i = 0; i < 6; i++) buyers.add(userRepository.save(user("concurrent-buyer-" + i)).getId());
		try (var executor = Executors.newFixedThreadPool(6)) {
			CountDownLatch ready = new CountDownLatch(6);
			CountDownLatch start = new CountDownLatch(1);
			List<Future<Boolean>> results = new ArrayList<>();
			for (int i = 0; i < 6; i++) {
				Long competingBuyer = buyers.get(i);
				results.add(executor.submit(() -> {
					ready.countDown();
					if (!start.await(10, TimeUnit.SECONDS)) throw new AssertionError("시작 신호 없음");
					try {
						orderService.createOrder(competingBuyer, new CreateOrderRequest(postId, 12000L, PaymentMethod.CARD));
						return true;
					} catch (BusinessException exception) {
						return false;
					}
				}));
			}
			assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
			start.countDown();
			int winners = 0;
			for (Future<Boolean> result : results) if (result.get(15, TimeUnit.SECONDS)) winners++;
			assertThat(winners).isEqualTo(1);
			assertThat(orderRepository.count()).isEqualTo(1);
			assertThat(paymentRepository.count()).isEqualTo(1);
		}
	}

	@Test
	void confirmationSellsPostAndCancellationReleasesIt() {
		CreateOrderResponse order = createOrder();
		when(tossPaymentsClient.confirm(any())).thenReturn(new TossPaymentResponse("key", order.orderId(), 12000L, "DONE"));
		paymentService.confirmPayment(buyerId, order.paymentId(), new ConfirmPaymentRequest(order.paymentId(), "key"));
		assertThat(postRepository.findById(postId).orElseThrow().getProductStatus()).isEqualTo(ProductStatus.SOLD_OUT);
		assertThat(orderRepository.findByOrderNumberAndUserId(order.orderId(), buyerId).orElseThrow().getOrderStatus()).isEqualTo(OrderStatus.PAID);
		when(tossPaymentsClient.cancel(any())).thenReturn(new TossPaymentResponse("key", order.orderId(), 12000L, "CANCELED"));
		paymentService.cancelPayment(buyerId, order.paymentId());
		assertThat(postRepository.findById(postId).orElseThrow().getProductStatus()).isEqualTo(ProductStatus.ON_SALE);
		createOrder();
		assertThatThrownBy(() -> paymentService.cancelPayment(buyerId, order.paymentId())).isInstanceOf(BusinessException.class);
		assertThat(postRepository.findById(postId).orElseThrow().getProductStatus()).isEqualTo(ProductStatus.RESERVED);
	}

	@Test
	void activeOrderBlocksEditingDeletingAndManualStatusChanges() {
		createOrder();
		assertThatThrownBy(() -> postService.updatePost(sellerId, postId,
			new UpdatePostRequest("변경", null, 1L, null, null, null, null))).isInstanceOf(BusinessException.class);
		assertThatThrownBy(() -> postService.updatePost(sellerId, postId,
			new UpdatePostRequest(null, null, null, null, null, ProductStatus.ON_SALE, null))).isInstanceOf(BusinessException.class);
		assertThatThrownBy(() -> postService.deletePost(sellerId, postId)).isInstanceOf(BusinessException.class);
		Post post = postRepository.findById(postId).orElseThrow();
		assertThat(post.getTitle()).isEqualTo("키링");
		assertThat(post.getPrice()).isEqualTo(12000L);
		assertThat(post.getDeletedAt()).isNull();
	}

	@Test
	void creatingPaymentAgainReusesReadyPayment() {
		CreateOrderResponse order = createOrder();
		var payment = paymentService.createPayment(buyerId, new CreatePaymentRequest(order.orderId(), 12000L, PaymentMethod.CARD));
		assertThat(payment.paymentId()).isEqualTo(order.paymentId());
		assertThat(paymentRepository.count()).isEqualTo(1);
	}

	@Test
	void unknownPgResultKeepsPaymentProcessing() {
		CreateOrderResponse order = createOrder();
		when(tossPaymentsClient.confirm(any())).thenThrow(new IllegalStateException("외부 결제 응답 유실"));
		assertThatThrownBy(() -> paymentService.confirmPayment(buyerId, order.paymentId(),
			new ConfirmPaymentRequest(order.paymentId(), "key"))).isInstanceOf(IllegalStateException.class);
		assertThat(paymentService.getPayment(buyerId, order.paymentId()).status()).isEqualTo(PaymentStatus.PROCESSING);
		assertThat(postRepository.findById(postId).orElseThrow().getProductStatus()).isEqualTo(ProductStatus.RESERVED);
	}

	@Test
	void transactionFailureRollsBackOrderPaymentAndReservation() {
		assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
			createOrder();
			throw new IllegalStateException("주문 처리 실패");
		})).isInstanceOf(IllegalStateException.class);
		assertThat(orderRepository.count()).isZero();
		assertThat(paymentRepository.count()).isZero();
		assertThat(postRepository.findById(postId).orElseThrow().getProductStatus()).isEqualTo(ProductStatus.ON_SALE);
	}

	@Test
	void reservationExpiresAfterFifteenMinutesAndOldExpiryCannotReleaseNewReservation() {
		LocalDateTime before = LocalDateTime.now();
		CreateOrderResponse first = createOrder();
		var order = orderRepository.findByOrderNumberAndUserId(first.orderId(), buyerId).orElseThrow();
		assertThat(order.getReservationExpiresAt()).isBetween(before.plusMinutes(15), LocalDateTime.now().plusMinutes(15));
		assertThat(reservationService.expireOrder(order.getId())).isFalse();
		makeExpired(first.orderId());
		assertThat(reservationService.expireOrder(order.getId())).isTrue();
		assertThat(paymentService.getPayment(buyerId, first.paymentId()).status()).isEqualTo(PaymentStatus.CANCELED);
		assertThat(orderRepository.findById(order.getId()).orElseThrow().getOrderStatus()).isEqualTo(OrderStatus.CANCELED);
		assertThat(postRepository.findById(postId).orElseThrow().getProductStatus()).isEqualTo(ProductStatus.ON_SALE);
		CreateOrderResponse second = createOrder();
		assertThat(reservationService.expireOrder(order.getId())).isFalse();
		assertThat(postRepository.findById(postId).orElseThrow().getActiveOrderNumber()).isEqualTo(second.orderId());
		assertThat(postRepository.findById(postId).orElseThrow().getProductStatus()).isEqualTo(ProductStatus.RESERVED);
		assertThatThrownBy(() -> paymentService.confirmPayment(buyerId, first.paymentId(),
			new ConfirmPaymentRequest(first.paymentId(), "old-key"))).isInstanceOf(BusinessException.class);
	}

	@Test
	void expiredReservationCannotStartPayment() {
		CreateOrderResponse order = createOrder();
		makeExpired(order.orderId());
		assertThatThrownBy(() -> paymentService.confirmPayment(buyerId, order.paymentId(),
			new ConfirmPaymentRequest(order.paymentId(), "key"))).isInstanceOf(BusinessException.class);
		assertThatThrownBy(() -> paymentService.createPayment(buyerId,
			new CreatePaymentRequest(order.orderId(), 12000L, PaymentMethod.CARD))).isInstanceOf(BusinessException.class);
		assertThat(paymentService.getPayment(buyerId, order.paymentId()).status()).isEqualTo(PaymentStatus.READY);
	}

	@Test
	void processingPaymentSurvivesExpiryAndRejectsSecondApproval() throws Exception {
		CreateOrderResponse order = createOrder();
		CountDownLatch pgStarted = new CountDownLatch(1);
		CountDownLatch pgFinish = new CountDownLatch(1);
		when(tossPaymentsClient.confirm(any())).thenAnswer(invocation -> {
			pgStarted.countDown();
			if (!pgFinish.await(15, TimeUnit.SECONDS)) throw new AssertionError("PG 완료 신호 없음");
			return new TossPaymentResponse("key", order.orderId(), 12000L, "DONE");
		});
		try (var executor = Executors.newSingleThreadExecutor()) {
			var approval = executor.submit(() -> paymentService.confirmPayment(buyerId, order.paymentId(),
				new ConfirmPaymentRequest(order.paymentId(), "key")));
			try {
				assertThat(pgStarted.await(10, TimeUnit.SECONDS)).isTrue();
				makeExpired(order.orderId());
				Long id = orderRepository.findIdByOrderNumberAndUserId(order.orderId(), buyerId).orElseThrow();
				assertThat(reservationService.expireOrder(id)).isFalse();
				assertThat(orderRepository.findExpiredReservationIds(LocalDateTime.now(),
					org.springframework.data.domain.PageRequest.of(0, 100))).isEmpty();
				assertThatThrownBy(() -> paymentService.confirmPayment(buyerId, order.paymentId(),
					new ConfirmPaymentRequest(order.paymentId(), "other-key"))).isInstanceOf(BusinessException.class);
				assertThat(paymentService.getPayment(buyerId, order.paymentId()).status()).isEqualTo(PaymentStatus.PROCESSING);
				assertThat(postRepository.findById(postId).orElseThrow().getProductStatus()).isEqualTo(ProductStatus.RESERVED);
			} finally {
				pgFinish.countDown();
			}
			assertThat(approval.get(10, TimeUnit.SECONDS).status()).isEqualTo(PaymentStatus.SUCCESS);
			assertThat(postRepository.findById(postId).orElseThrow().getProductStatus()).isEqualTo(ProductStatus.SOLD_OUT);
		}
	}

	@Test
	void snapshotSurvivesProductAndProfileChangesAfterCancellation() {
		CreateOrderResponse response = createOrder();
		makeExpired(response.orderId());
		var order = orderRepository.findByOrderNumberAndUserId(response.orderId(), buyerId).orElseThrow();
		reservationService.expireOrder(order.getId());
		postService.updatePost(sellerId, postId, new UpdatePostRequest("수정 상품명", null, 999L, null, null, null, null));
		jdbc.update("update users set nickname = ? where id = ?", "수정 닉네임", sellerId);
		var snapshot = orderRepository.findById(order.getId()).orElseThrow();
		assertThat(snapshot.getSnapshotPostId()).isEqualTo(postId);
		assertThat(snapshot.getPostTitle()).isEqualTo("키링");
		assertThat(snapshot.getAmount()).isEqualTo(12000L);
		assertThat(snapshot.getSellerId()).isEqualTo(sellerId);
		assertThat(snapshot.getSellerNickname()).isEqualTo("seller");
	}

	@Test
	void paidOrderAlsoBlocksChangesAndUnknownCancellationKeepsSoldOut() {
		CreateOrderResponse order = createOrder();
		when(tossPaymentsClient.confirm(any())).thenReturn(new TossPaymentResponse("key", order.orderId(), 12000L, "DONE"));
		paymentService.confirmPayment(buyerId, order.paymentId(), new ConfirmPaymentRequest(order.paymentId(), "key"));
		assertThatThrownBy(() -> postService.deletePost(sellerId, postId)).isInstanceOf(BusinessException.class);
		assertThatThrownBy(() -> postService.updatePost(sellerId, postId,
			new UpdatePostRequest(null, null, null, null, null, ProductStatus.ON_SALE, null))).isInstanceOf(BusinessException.class);
		when(tossPaymentsClient.cancel(any())).thenThrow(new IllegalStateException("취소 결과 유실"));
		assertThatThrownBy(() -> paymentService.cancelPayment(buyerId, order.paymentId())).isInstanceOf(IllegalStateException.class);
		assertThat(paymentService.getPayment(buyerId, order.paymentId()).status()).isEqualTo(PaymentStatus.PROCESSING);
		assertThat(postRepository.findById(postId).orElseThrow().getProductStatus()).isEqualTo(ProductStatus.SOLD_OUT);
	}

	@Test
	void databaseFailureAfterPgSuccessDoesNotRestoreReadyOrReleaseReservation() {
		CreateOrderResponse order = createOrder();
		paymentTransactionService.startConfirm(buyerId, order.paymentId(), new ConfirmPaymentRequest(order.paymentId(), "key"));
		assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
			paymentTransactionService.completeConfirm(buyerId, order.paymentId(), "key");
			throw new IllegalStateException("승인 결과 저장 실패");
		})).isInstanceOf(IllegalStateException.class);
		makeExpired(order.orderId());
		Long id = orderRepository.findIdByOrderNumberAndUserId(order.orderId(), buyerId).orElseThrow();
		assertThat(reservationService.expireOrder(id)).isFalse();
		assertThat(paymentService.getPayment(buyerId, order.paymentId()).status()).isEqualTo(PaymentStatus.PROCESSING);
		assertThat(postRepository.findById(postId).orElseThrow().getProductStatus()).isEqualTo(ProductStatus.RESERVED);
	}

	@Test
	void scheduledScanCancelsExpiredReadyOrder() {
		CreateOrderResponse order = createOrder();
		makeExpired(order.orderId());
		reservationScheduler.expireReservations();
		assertThat(paymentService.getPayment(buyerId, order.paymentId()).status()).isEqualTo(PaymentStatus.CANCELED);
		assertThat(postRepository.findById(postId).orElseThrow().getActiveOrderNumber()).isNull();
		assertThat(postRepository.findById(postId).orElseThrow().getProductStatus()).isEqualTo(ProductStatus.ON_SALE);
	}

	@Test
	void orderLevelDatabaseConstraintRejectsAnotherPayment() {
		CreateOrderResponse response = createOrder();
		var order = orderRepository.findByOrderNumberAndUserId(response.orderId(), buyerId).orElseThrow();
		assertThatThrownBy(() -> paymentRepository.saveAndFlush(com.kitschcatch.backend.domain.order.entity.Payment.builder()
			.paymentId("PAY-duplicate").order(order).amount(12000L).paymentMethod(PaymentMethod.CARD)
			.paymentStatus(PaymentStatus.READY).build())).isInstanceOf(DataIntegrityViolationException.class);
		assertThat(paymentRepository.count()).isEqualTo(1);
	}

	@Test
	void sellerChangeWaitsForConcurrentReservationAndThenFails() throws Exception {
		try (var executor = Executors.newSingleThreadExecutor()) {
			List<Future<?>> attempts = new ArrayList<>();
			new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
				postRepository.findByIdForUpdate(postId).orElseThrow();
				CountDownLatch entered = new CountDownLatch(1);
				Future<?> change = executor.submit(() -> {
					entered.countDown();
					postService.updatePost(sellerId, postId,
						new UpdatePostRequest("경합 수정", null, null, null, null, ProductStatus.ON_SALE, null));
				});
				attempts.add(change);
				try {
					assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
					assertThatThrownBy(() -> change.get(200, TimeUnit.MILLISECONDS))
						.isInstanceOf(java.util.concurrent.TimeoutException.class);
				} catch (InterruptedException exception) {
					Thread.currentThread().interrupt();
					throw new AssertionError(exception);
				}
				createOrder();
			});
			assertThatThrownBy(() -> attempts.getFirst().get(10, TimeUnit.SECONDS))
				.isInstanceOf(java.util.concurrent.ExecutionException.class).hasCauseInstanceOf(BusinessException.class);
			assertThat(postRepository.findById(postId).orElseThrow().getTitle()).isEqualTo("키링");
			assertThat(postRepository.findById(postId).orElseThrow().getProductStatus()).isEqualTo(ProductStatus.RESERVED);
		}
	}

	private void makeExpired(String orderNumber) {
		jdbc.update("update orders set reservation_expires_at = ? where order_number = ?", LocalDateTime.now().minusMinutes(1), orderNumber);
	}

	private CreateOrderResponse createOrder() {
		return orderService.createOrder(buyerId, new CreateOrderRequest(postId, 12000L, PaymentMethod.CARD));
	}

	private User user(String nickname) {
		return User.builder().nickname(nickname).email(nickname + "@example.com")
			.authProvider(AuthProvider.KAKAO).providerUserId(nickname).build();
	}
}
