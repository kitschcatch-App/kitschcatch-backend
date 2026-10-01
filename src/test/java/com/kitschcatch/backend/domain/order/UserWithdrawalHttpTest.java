// 탈퇴 HTTP 계약과 거래 보존·인증 차단·동시성 불변식을 실제 서버에서 검증한다.
package com.kitschcatch.backend.domain.order;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import com.kitschcatch.backend.domain.auth.entity.RefreshToken;
import com.kitschcatch.backend.domain.auth.oidc.KakaoOidcTokenVerifier;
import com.kitschcatch.backend.domain.auth.oidc.KakaoOidcUser;
import com.kitschcatch.backend.domain.auth.repository.RefreshTokenRepository;
import com.kitschcatch.backend.domain.chat.entity.ChatRoom;
import com.kitschcatch.backend.domain.chat.entity.ChatMessage;
import com.kitschcatch.backend.domain.chat.repository.ChatRoomRepository;
import com.kitschcatch.backend.domain.chat.repository.ChatMessageRepository;
import com.kitschcatch.backend.domain.order.dto.CreateOrderRequest;
import com.kitschcatch.backend.domain.order.entity.PaymentMethod;
import com.kitschcatch.backend.domain.order.service.OrderService;
import com.kitschcatch.backend.domain.order.toss.TossPaymentResponse;
import com.kitschcatch.backend.domain.post.entity.*;
import com.kitschcatch.backend.domain.post.repository.PostFavoriteRepository;
import com.kitschcatch.backend.domain.post.service.PostFavoriteService;
import com.kitschcatch.backend.domain.follow.entity.UserFollow;
import com.kitschcatch.backend.domain.follow.repository.UserFollowRepository;
import com.kitschcatch.backend.domain.follow.service.FollowService;
import com.kitschcatch.backend.domain.store.entity.Store;
import com.kitschcatch.backend.domain.store.repository.StoreRepository;
import com.kitschcatch.backend.domain.store.repository.StoreFavoriteRepository;
import com.kitschcatch.backend.domain.store.service.StoreFavoriteService;
import com.kitschcatch.backend.domain.notification.*;
import com.kitschcatch.backend.domain.user.service.UserWithdrawalService;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.List;
import java.util.concurrent.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties={
    "spring.datasource.url=jdbc:h2:mem:issue57;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver","spring.datasource.username=sa","spring.datasource.password=",
    "spring.jpa.hibernate.ddl-auto=create-drop","kakao.oauth.native-app-key=test-native-app-key",
    "app.orders.expiration-enabled=false","app.payments.recovery.enabled=false","app.s3.public-base-url=https://cdn.example.test","springdoc.api-docs.enabled=true"
})
class UserWithdrawalHttpTest extends UserWithdrawalHttpContract {}

abstract class UserWithdrawalHttpContract extends OrderLifecycleHttpFixture {
    @Autowired RefreshTokenRepository refreshTokens;
    @Autowired com.kitschcatch.backend.domain.order.repository.PaymentAttemptRepository attempts;
    @Autowired com.kitschcatch.backend.domain.order.repository.PaymentRepository paymentRecords;
    @Autowired ChatRoomRepository rooms;
    @Autowired ChatMessageRepository messages;
    @Autowired UserWithdrawalService withdrawal;
    @Autowired OrderService orderService;
    @Autowired PostFavoriteRepository postFavorites;
    @Autowired PostFavoriteService favoriteService;
    @Autowired UserFollowRepository follows;
    @Autowired FollowService followService;
    @Autowired NotificationRepository notificationRecords;
    @Autowired NotificationService notificationService;
    @Autowired DeviceTokenRepository deviceTokens;
    @Autowired DeviceTokenTransactionService tokenService;
    @Autowired PushDeliveryRepository deliveries;
    @Autowired PushAttemptRepository pushAttempts;
    @Autowired StoreRepository stores;
    @Autowired StoreFavoriteRepository storeFavorites;
    @Autowired StoreFavoriteService storeFavoriteService;
    @Autowired PlatformTransactionManager transactionManager;
    @MockitoBean KakaoOidcTokenVerifier verifier;

    @Override @BeforeEach
    void resetData() {
        for (String table : List.of("push_attempts","push_deliveries","notifications","device_tokens",
            "post_favorites","user_follows","store_favorites","store_business_hours","stores",
            "refresh_tokens","login_nonces","chat_messages","chat_rooms"))
            jdbc.update("DELETE FROM "+table);
        super.resetData();
        reset(verifier);
    }

    Response withdraw(String token) { return request("DELETE","/api/users/me",token,null); }

    @Test void ownAccountOnlyAndNextRequestCannotAuthenticateOrLeakProfile() {
        buyer.registerProfile("개인닉네임","개인닉네임","profiles/"+buyer.getId()+"/private.png",Instant.now());
        users.saveAndFlush(buyer);
        String refresh=tokens.createRefreshToken(buyer.getId());
        refreshTokens.saveAndFlush(RefreshToken.builder().user(buyer).tokenHash(tokens.hashToken(refresh))
            .expiresAt(LocalDateTime.now().plusDays(1)).build());
        assertError(withdraw(null),401,"AUTH_004");

        var response=request("DELETE","/api/users/me?userId="+seller.getId(),buyerToken,Map.of("userId",seller.getId()));
        assertThat(response.status()).isEqualTo(200);
        assertThat(response.body()).containsEntry("success",true).doesNotContainKeys("data","error");
        var closed=users.findById(buyer.getId()).orElseThrow();
        assertThat(closed.getWithdrawnAt()).isNotNull();
        assertThat(closed.getNickname()).isEqualTo("탈퇴한 사용자");
        assertThat(closed.getEmail()).endsWith("@account.invalid").doesNotContain(buyer.getEmail());
        assertThat(closed.getNicknameKey()).isNull();
        assertThat(closed.getProfileImageKey()).isNull();
        assertThat(closed.getProfileRegisteredAt()).isNull();
        assertThat(closed.getProviderUserId()).isEqualTo(buyer.getProviderUserId());
        assertThat(users.findById(seller.getId()).orElseThrow().isActive()).isTrue();
        assertThat(refreshTokens.count()).isZero();
        for(String path:List.of("/api/users/me","/api/users/nickname-availability?nickname=valid","/api/users/me/purchase-orders"))
            assertError(get(path,buyerToken),401,"AUTH_004");
        assertError(withdraw(buyerToken),401,"AUTH_004");
        assertThat(request("POST","/api/auth/token/refresh",null,Map.of("refreshToken",refresh)).status()).isEqualTo(401);
        var time=closed.getWithdrawnAt();
        withdrawal.withdraw(buyer.getId());
        assertThat(users.findById(buyer.getId()).orElseThrow().getWithdrawnAt()).isEqualTo(time);
    }

    @Test void withdrawalClearsNewProfileRelationsNotificationsAndDeviceOwnership() {
        buyer.registerProfile("공개 닉네임","공개 닉네임",null,Instant.now());
        buyer.updatePublicProfile("vacatedname",true,"개인 소개",true);
        users.saveAndFlush(buyer);
        var post=salePost();
        postFavorites.saveAndFlush(new PostFavorite(buyer,post));
        follows.saveAndFlush(new UserFollow(buyer,seller));
        follows.saveAndFlush(new UserFollow(seller,buyer));
        var device=deviceTokens.saveAndFlush(new DeviceToken(buyer.getId(),"synthetic-device-token","IOS"));
        var notification=notificationRecords.saveAndFlush(
            new Notification(buyer.getId(),"chat:withdraw-test",NotificationType.CHAT_MESSAGE,"room"));
        var otherNotification=notificationRecords.saveAndFlush(
            new Notification(seller.getId(),"chat:other-user",NotificationType.CHAT_MESSAGE,"room"));
        var delivery=deliveries.saveAndFlush(new PushDelivery(notification.getId(),device));
        delivery.finish(PushGateway.Result.retry("TEST_RETRY"));
        deliveries.saveAndFlush(delivery);
        pushAttempts.saveAndFlush(new PushAttempt(delivery));

        assertThat(withdraw(buyerToken).status()).isEqualTo(200);
        var closed=users.findById(buyer.getId()).orElseThrow();
        assertThat(closed.getUsername()).isNull();
        assertThat(closed.getBio()).isNull();
        assertThat(postFavorites.count()).isZero();
        assertThat(follows.count()).isZero();
        assertThat(notificationRecords.findByUserId(buyer.getId(),org.springframework.data.domain.PageRequest.of(0,10)))
            .isEmpty();
        assertThat(notificationRecords.existsById(otherNotification.getId())).isTrue();
        assertThat(deliveries.count()).isZero();
        assertThat(pushAttempts.count()).isZero();
        var inactive=deviceTokens.findById(device.getId()).orElseThrow();
        assertThat(inactive.isActive()).isFalse();
        assertThat(inactive.getOwnershipVersion()).isEqualTo(1);

        notificationService.record(buyer.getId(),"chat:late",NotificationType.CHAT_MESSAGE,"room");
        assertThat(notificationRecords.existsByEventKeyAndUserId("chat:late",buyer.getId())).isFalse();
        assertThatThrownBy(() -> tokenService.register(buyer.getId(),new DeviceTokenRequest("late-token","IOS")))
            .isInstanceOf(com.kitschcatch.backend.global.exception.BusinessException.class);
        assertThatThrownBy(() -> favoriteService.register(buyer.getId(),post.getId()))
            .isInstanceOf(com.kitschcatch.backend.global.exception.BusinessException.class);
        assertThatThrownBy(() -> followService.change(buyer.getId(),seller.getId(),true))
            .isInstanceOf(com.kitschcatch.backend.global.exception.BusinessException.class);
        assertThatThrownBy(() -> followService.change(seller.getId(),buyer.getId(),true))
            .isInstanceOf(com.kitschcatch.backend.global.exception.BusinessException.class);
        stranger.registerProfile("새 사용자","새 사용자",null,Instant.now());
        stranger.updatePublicProfile("vacatedname",true,null,true);
        users.saveAndFlush(stranger);
        assertThat(users.findById(stranger.getId()).orElseThrow().getUsername()).isEqualTo("vacatedname");
    }

    @Test void canceledTradeChatForeignKeysAndImmutableSnapshotsSurvive() {
        var o=order(buyer,seller,true); approve(o); cancel(o);
        var post=posts.findById(o.postId()).orElseThrow();
        var room=rooms.saveAndFlush(ChatRoom.create(post,buyer,seller));
        var message=messages.saveAndFlush(ChatMessage.createTextMessage(room,seller,"거래 기록"));
        var before=jdbc.queryForMap("select * from orders where order_number=?",o.orderId());
        assertThat(withdraw(sellerToken).status()).isEqualTo(200);
        assertThat(jdbc.queryForMap("select * from orders where order_number=?",o.orderId())).isEqualTo(before);
        assertThat(rooms.existsById(room.getId())).isTrue();
        assertThat(messages.existsById(message.getId())).isTrue();
        assertThat(jdbc.queryForObject("select seller_id from chat_rooms where id=?",Long.class,room.getId())).isEqualTo(seller.getId());
        assertThat(posts.findById(o.postId()).orElseThrow().getDeletedAt()).isNotNull();
        assertThat(get("/api/posts/"+o.postId(),buyerToken).status()).isEqualTo(404);
        var detail=get("/api/orders/"+o.orderId(),buyerToken);
        assertThat(detail.status()).isEqualTo(200);
        assertThat(detail.body().toString()).contains("판매자","구매자").doesNotContain(seller.getEmail(),seller.getProviderUserId());
        verify(s3,never()).deleteObject(any(software.amazon.awssdk.services.s3.model.DeleteObjectRequest.class));
    }

    @ParameterizedTest @ValueSource(strings={
        "PENDING","PAID","SETTLEMENT_WAITING","SETTLEMENT_PROCESSING","SETTLEMENT_UNKNOWN","SETTLEMENT_FAILED",
        "MISSING_SETTLEMENT","REFUND_REQUESTED","REFUND_PROCESSING","PAYMENT_PROCESSING","RECOVERY_PENDING",
        "RECOVERY_REVIEW_REQUIRED","ATTEMPT_UNKNOWN","ATTEMPT_PROCESSING","CANCEL_FAILED","CANCEL_PREPARED",
        "CANCELED_SUCCESS","MISSING_PAYMENT"
    })
    void unfinishedOrUncertainTradeBlocksBothParticipants(String state) {
        var o=order(buyer,seller,false);
        jdbc.update("update orders set order_status='CANCELED' where order_number=?",o.orderId());
        jdbc.update("update payments set payment_status='CANCELED',recovery_state='NONE'");
        switch(state) {
            case "PENDING","PAID" -> jdbc.update("update orders set order_status=?",state);
            case "MISSING_SETTLEMENT" -> {
                jdbc.update("update orders set order_status='PURCHASE_CONFIRMED'");
                jdbc.update("update payments set payment_status='SUCCESS'");
            }
            case "PAYMENT_PROCESSING" -> jdbc.update("update payments set payment_status='PROCESSING'");
            case "RECOVERY_PENDING","RECOVERY_REVIEW_REQUIRED" -> jdbc.update("update payments set recovery_state=?",state.substring(9));
            case "ATTEMPT_UNKNOWN","ATTEMPT_PROCESSING" -> jdbc.update("update payment_attempts set attempt_status=?",state.substring(8));
            case "CANCEL_FAILED","CANCEL_PREPARED" -> jdbc.update("update payment_attempts set operation='CANCEL',attempt_status=?",state.substring(7));
            case "CANCELED_SUCCESS" -> jdbc.update("update payments set payment_status='SUCCESS'");
            case "MISSING_PAYMENT" -> { jdbc.update("delete from payment_attempts"); jdbc.update("delete from payments"); }
            default -> {
                if(state.startsWith("SETTLEMENT_")) {
                    jdbc.update("update orders set order_status='PURCHASE_CONFIRMED',settlement_id='SET-test',settlement_status=?",state.substring(11));
                    jdbc.update("update payments set payment_status='SUCCESS'");
                } else jdbc.update("update orders set refund_id='REF-test',refund_status=?",state.substring(7));
            }
        }
        for(String token:List.of(buyerToken,sellerToken)) {
            var result=withdraw(token);
            assertError(result,409,"USER_057_001");
            assertThat(result.body().toString()).doesNotContain(o.orderId(),o.paymentId(),seller.getEmail());
        }
        assertThat(users.findById(buyer.getId()).orElseThrow().isActive()).isTrue();
        assertThat(users.findById(seller.getId()).orElseThrow().isActive()).isTrue();
    }

    @ParameterizedTest @ValueSource(strings={"CANCELED","REFUNDED","PURCHASE_CONFIRMED","FAILED_CONFIRM_CANCELED"})
    void finalizedTradesAllowWithdrawal(String state) {
        var o=order(buyer,seller,false);
        jdbc.update("update orders set order_status=?",state.equals("FAILED_CONFIRM_CANCELED")?"CANCELED":state);
        jdbc.update("update payment_attempts set attempt_status='EXPIRED'");
        jdbc.update("update payments set payment_status='CANCELED'");
        if(state.equals("PURCHASE_CONFIRMED")) {
            jdbc.update("update orders set settlement_id='SET-test',settlement_status='COMPLETED'");
            jdbc.update("update payments set payment_status='SUCCESS'");
        }
        if(state.equals("REFUNDED")) jdbc.update("update orders set refund_id='REF-test',refund_status='COMPLETED'");
        if(state.equals("FAILED_CONFIRM_CANCELED")) {
            jdbc.update("update payments set payment_status='FAILED'");
            jdbc.update("update payment_attempts set attempt_status='FAILED'");
        }
        assertThat(withdraw(buyerToken).status()).isEqualTo(200);
        assertThat(withdraw(sellerToken).status()).isEqualTo(200);
        assertThat(jdbc.queryForObject("select count(*) from orders",Integer.class)).isEqualTo(1);
    }


    @Test void resolvedHistoricalCancellationFailureDoesNotBlockAccountClosure() {
        var o=order(buyer,seller,false); approve(o);
        Long paymentId=jdbc.queryForObject("select id from payments",Long.class);
        attempts.saveAndFlush(com.kitschcatch.backend.domain.order.entity.PaymentAttempt.builder()
            .attemptId("ATT-old-cancel").payment(paymentRecords.findById(paymentId).orElseThrow()).sequenceNumber(2)
            .operation(com.kitschcatch.backend.domain.order.entity.PaymentAttemptOperation.CANCEL)
            .attemptStatus(com.kitschcatch.backend.domain.order.entity.PaymentAttemptStatus.FAILED)
            .pgOrderId(o.orderId()).amount(12000L).pgIdempotencyKey("historical-failed-cancel")
            .requestedAt(LocalDateTime.now().minusMinutes(1)).nextCheckAt(LocalDateTime.now()).build());
        jdbc.update("update payments set current_attempt_id='ATT-old-cancel'");
        cancel(o);
        assertThat(withdraw(buyerToken).status()).isEqualTo(200);
        assertThat(withdraw(sellerToken).status()).isEqualTo(200);
        assertThat(jdbc.queryForObject("select count(*) from payment_attempts where attempt_status='FAILED'",Integer.class)).isEqualTo(1);
    }
    @Test void socialReloginNeverRestoresClosedIdentityAndConsumesVerifiedNonce() {
        assertThat(withdraw(buyerToken).status()).isEqualTo(200);
        when(verifier.verify(any(),any())).thenReturn(new KakaoOidcUser(buyer.getProviderUserId(),buyer.getEmail(),"복구 닉네임"));
        var nonce=request("POST","/api/auth/kakao/nonce",null,null);
        assertThat(nonce.status()).isEqualTo(200);
        assertThat(request("POST","/api/auth/kakao/mobile-login",null,Map.of("idToken","test-id-token","nonce",nonce.data().get("nonce"))).status()).isEqualTo(401);
        assertThat(users.count()).isEqualTo(3);
        assertThat(refreshTokens.count()).isZero();
        assertThat(jdbc.queryForObject("select count(*) from login_nonces where consumed_at is not null",Integer.class)).isEqualTo(1);
        assertThat(users.findById(buyer.getId()).orElseThrow().isActive()).isFalse();
    }

    Post salePost() { return posts.saveAndFlush(Post.builder().user(seller).title("경쟁 상품").description("검증").price(12000L)
        .productCategory(ProductCategory.GOODS).productCondition(ProductCondition.NEW).productStatus(ProductStatus.ON_SALE).build()); }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void withdrawalHoldingUserLockWinsAgainstNewOrder(boolean closeSeller) throws Exception {
        var post=salePost(); var attempted=new CountDownLatch(1);
        try(var pool=Executors.newSingleThreadExecutor()) {
            Future<Response>[] pending=new Future[1];
            new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
                withdrawal.withdraw(closeSeller?seller.getId():buyer.getId());
                pending[0]=pool.submit(() -> {attempted.countDown();return request("POST","/api/orders",buyerToken,
                    Map.of("postId",post.getId(),"amount",12000,"paymentMethod","CARD"));});
                await(attempted);
                assertThatThrownBy(() -> pending[0].get(300,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            });
            assertThat(pending[0].get(10,TimeUnit.SECONDS).status()).isEqualTo(401);
        }
        assertThat(jdbc.queryForObject("select count(*) from orders",Integer.class)).isZero();
    }

    @Test void newOrderHoldingParticipantLocksWinsAgainstSellerWithdrawal() throws Exception {
        var post=salePost(); var attempted=new CountDownLatch(1);
        try(var pool=Executors.newSingleThreadExecutor()) {
            Future<Response>[] pending=new Future[1];
            new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
                orderService.createOrder(buyer.getId(),new CreateOrderRequest(post.getId(),12000L,PaymentMethod.CARD));
                pending[0]=pool.submit(() -> {attempted.countDown();return withdraw(sellerToken);});
                await(attempted);
                assertThatThrownBy(() -> pending[0].get(300,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            });
            assertError(pending[0].get(10,TimeUnit.SECONDS),409,"USER_057_001");
        }
        assertThat(users.findById(seller.getId()).orElseThrow().isActive()).isTrue();
    }

    @Test void withdrawalIsBlockedWhilePgConfirmationRunsOutsideTransaction() throws Exception {
        var o=order(buyer,seller,false); var entered=new CountDownLatch(1); var finish=new CountDownLatch(1);
        when(toss.confirm(any())).thenAnswer(call -> {
            entered.countDown(); assertThat(finish.await(10,TimeUnit.SECONDS)).isTrue();
            return new TossPaymentResponse("key",o.orderId(),12000L,"DONE");
        });
        try(var pool=Executors.newSingleThreadExecutor()) {
            var payment=pool.submit(() -> request("POST","/api/payments/"+o.paymentId()+"/confirm",buyerToken,
                Map.of("paymentId",o.paymentId(),"paymentKey","key")));
            try { await(entered); assertError(withdraw(buyerToken),409,"USER_057_001");
                assertError(withdraw(sellerToken),409,"USER_057_001"); }
            finally {finish.countDown();}
            assertThat(payment.get(10,TimeUnit.SECONDS).status()).isEqualTo(200);
        }
    }

    @Test void existingPaymentLockMustFinishBeforeWithdrawalChecksResults() throws Exception {
        var o=order(buyer,seller,false); var attempted=new CountDownLatch(1);
        try(var pool=Executors.newSingleThreadExecutor()) {
            Future<Response>[] pending=new Future[1];
            new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
                jdbc.queryForList("select id from payments for update");
                jdbc.update("update orders set order_status='CANCELED'");
                jdbc.update("update payments set payment_status='CANCELED'");
                jdbc.update("update payment_attempts set attempt_status='EXPIRED'");
                pending[0]=pool.submit(() -> {attempted.countDown();return withdraw(buyerToken);});
                await(attempted);
                assertThatThrownBy(() -> pending[0].get(300,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            });
            assertThat(pending[0].get(10,TimeUnit.SECONDS).status()).isEqualTo(200);
        }
    }


    @Test void preloadedRecipientCannotReceiveNewNotificationAfterWithdrawal() {
        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            var preloaded=users.findById(buyer.getId()).orElseThrow();
            assertThat(preloaded.isActive()).isTrue();
            assertThat(withdraw(buyerToken).status()).isEqualTo(200);
            notificationService.record(buyer.getId(),"chat:late-preloaded",
                NotificationType.CHAT_MESSAGE,"room");
        });
        assertThat(notificationRecords.existsByEventKeyAndUserId("chat:late-preloaded",buyer.getId()))
            .isFalse();
    }

    @Test void preloadedUserCannotAddStoreFavoriteAfterWithdrawal() {
        var store=stores.saveAndFlush(Store.builder().name("보존 매장").region("서울")
            .address("서울시").latitude(37.0).longitude(127.0).build());
        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            var preloaded=users.findById(buyer.getId()).orElseThrow();
            assertThat(preloaded.isActive()).isTrue();
            assertThat(withdraw(buyerToken).status()).isEqualTo(200);
            assertThatThrownBy(() -> storeFavoriteService.register(buyer.getId(),store.getId()))
                .isInstanceOf(com.kitschcatch.backend.global.exception.BusinessException.class);
            tx.setRollbackOnly();
        });
        assertThat(storeFavorites.count()).isZero();
    }

    @Test void withdrawalWaitsForInFlightPushDeliveryBeforeDeactivatingDevice() throws Exception {
        var device=deviceTokens.saveAndFlush(new DeviceToken(buyer.getId(),"in-flight-device-token","IOS"));
        var notification=notificationRecords.saveAndFlush(
            new Notification(buyer.getId(),"chat:in-flight",NotificationType.CHAT_MESSAGE,"room"));
        var delivery=deliveries.saveAndFlush(new PushDelivery(notification.getId(),device));
        var attempted=new CountDownLatch(1);
        try(var pool=Executors.newSingleThreadExecutor()) {
            Future<Response>[] pending=new Future[1];
            new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
                jdbc.queryForList("select id from push_deliveries where id = ? for update",delivery.getId());
                jdbc.queryForList("select id from device_tokens where id = ? for update",device.getId());
                pending[0]=pool.submit(() -> {attempted.countDown();return withdraw(buyerToken);});
                await(attempted);
                assertThatThrownBy(() -> pending[0].get(300,TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);
            });
            assertThat(pending[0].get(10,TimeUnit.SECONDS).status()).isEqualTo(200);
        }
        assertThat(deliveries.count()).isZero();
        assertThat(notificationRecords.existsById(notification.getId())).isFalse();
        assertThat(deviceTokens.findById(device.getId()).orElseThrow().isActive()).isFalse();
    }

    @Test void withdrawalHoldingUserLockPreventsRefreshFromIssuingAnyToken() throws Exception {
        String refresh=tokens.createRefreshToken(buyer.getId());
        refreshTokens.saveAndFlush(RefreshToken.builder().user(buyer).tokenHash(tokens.hashToken(refresh))
            .expiresAt(LocalDateTime.now().plusDays(1)).build());
        var attempted=new CountDownLatch(1);
        try(var pool=Executors.newSingleThreadExecutor()) {
            var pending=new java.util.concurrent.atomic.AtomicReference<Future<Response>>();
            new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
                withdrawal.withdraw(buyer.getId());
                pending.set(pool.submit(() -> {attempted.countDown();return request("POST","/api/auth/token/refresh",null,Map.of("refreshToken",refresh));}));
                await(attempted);
                assertThatThrownBy(() -> pending.get().get(300,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            });
            assertThat(pending.get().get(10,TimeUnit.SECONDS).status()).isEqualTo(401);
        }
        assertThat(refreshTokens.count()).isZero();
    }

    @Test void concurrentWithdrawalRequestsRemainIdempotent() throws Exception {
        var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var first=pool.submit(() -> {await(start);return withdraw(buyerToken);});
            var second=pool.submit(() -> {await(start);return withdraw(buyerToken);});
            start.countDown();
            assertThat(List.of(first.get(10,TimeUnit.SECONDS).status(),second.get(10,TimeUnit.SECONDS).status()))
                .allSatisfy(code -> assertThat(code).isIn(200,401)).contains(200);
        }
        var closed=users.findById(buyer.getId()).orElseThrow();
        assertThat(closed.isActive()).isFalse();
        assertThat(withdraw(buyerToken).status()).isEqualTo(401);
    }

    @Test void withdrawalOperationIsPublishedWithoutAnyUserIdInput() {
        var doc=get("/v3/api-docs",null);
        var operation=child(child(child(doc.body(),"paths"),"/api/users/me"),"delete");
        assertThat(operation.get("security")).isNotNull();
        assertThat(operation).doesNotContainKey("requestBody");
    }
    static void await(CountDownLatch latch) {
        try { assertThat(latch.await(10,TimeUnit.SECONDS)).isTrue(); }
        catch(InterruptedException e) {Thread.currentThread().interrupt();throw new AssertionError(e);}
    }
}
