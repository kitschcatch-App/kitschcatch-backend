// 거래 API와 결제 복구가 수신자별 알림을 한 번씩 저장하는지 검증한다.
package com.kitschcatch.backend.domain.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.kitschcatch.backend.domain.order.dto.ShipmentRequest;
import com.kitschcatch.backend.domain.order.dto.TossPaymentWebhookRequest;
import com.kitschcatch.backend.domain.order.repository.PaymentAttemptRepository;
import com.kitschcatch.backend.domain.order.repository.PaymentWebhookEventRepository;
import com.kitschcatch.backend.domain.order.service.PaymentRecoveryService;
import com.kitschcatch.backend.domain.order.service.PaymentWebhookProcessingService;
import com.kitschcatch.backend.domain.order.service.PaymentWebhookService;
import com.kitschcatch.backend.domain.order.service.ShipmentService;
import com.kitschcatch.backend.domain.order.settlement.SettlementGateway;
import com.kitschcatch.backend.domain.order.toss.TossPaymentResponse;
import com.kitschcatch.backend.global.exception.BusinessException;
import com.kitschcatch.backend.global.exception.ErrorCode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.datasource.url=jdbc:h2:mem:trade-notices;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password=",
    "spring.jpa.hibernate.ddl-auto=create-drop", "kakao.oauth.native-app-key=test-native-app-key",
    "app.orders.expiration-enabled=false", "app.payments.recovery.enabled=false",
    "app.notifications.trade-enabled=true", "app.refunds.operator-user-ids=900000",
    "app.settlement.operator-user-ids=900000", "app.settlement.fee-basis-points=500"
})
class TradeNotificationHttpTest extends TradeNotificationHttpContract {}

abstract class TradeNotificationHttpContract extends OrderLifecycleHttpFixture {
    private static final String A = "123456789012";
    private static final String B = "987654321098";
    private static final long OPERATOR = 900000L;
    @Autowired ShipmentService shipment;
    @Autowired TransactionTemplate transactions;
    @Autowired PaymentRecoveryService recovery;
    @Autowired PaymentWebhookService webhooks;
    @Autowired PaymentAttemptRepository attempts;
    @Autowired PaymentWebhookEventRepository webhookEvents;
    @MockitoBean SettlementGateway gateway;
    String operatorToken;

    @BeforeEach
    @Override
    void resetData() {
        for (String table : List.of("push_attempts", "push_deliveries", "device_tokens", "notifications")) {
            jdbc.update("DELETE FROM " + table);
        }
        super.resetData();
        jdbc.update("""
            INSERT INTO users(id,nickname,email,auth_provider,provider_user_id,created_at)
            VALUES(900000,'운영자','trade-notice@example.test','KAKAO','trade-notice-operator',CURRENT_TIMESTAMP)
            """);
        operatorToken=tokens.createAccessToken(OPERATOR);
        when(gateway.configured()).thenReturn(true);
        jdbc.update("""
            INSERT INTO settlement_recipients(seller_id,provider_seller_id,registered_by,verified_at)
            VALUES(?,?,?,CURRENT_TIMESTAMP)
            """, seller.getId(), destination(), OPERATOR);
    }

    @Test
    void realShipmentEditsNotifyOnlyBuyerAndCanReturnToAnEarlierNumber() {
        var order=paid();
        assertError(ship(order,"POST",A,buyerToken),403,"SHIPMENT_002");
        for (String token : List.of("synthetic-buyer-one", "synthetic-buyer-two")) {
            assertThat(request("POST","/api/users/me/device-tokens",buyerToken,
                Map.of("token",token,"platform","ANDROID")).status()).isEqualTo(200);
        }
        for (int i=0;i<2;i++) {
            assertThat(ship(order,"POST",A,sellerToken).status()).isEqualTo(200);
        }
        for (String number : List.of(A,B,B,A)) {
            assertThat(ship(order,"PATCH",number,sellerToken).status()).isEqualTo(200);
        }
        assertThat(types(order,buyer.getId())).containsExactly("SHIPMENT_REGISTERED","SHIPMENT_UPDATED","SHIPMENT_UPDATED");
        assertThat(types(order,seller.getId())).isEmpty();
        assertThat(types(order,stranger.getId())).isEmpty();
        assertThat(jdbc.queryForObject("SELECT shipment_revision FROM orders WHERE order_number=?",Long.class,
            order.orderId())).isEqualTo(3L);
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM push_deliveries d JOIN notifications n ON n.id=d.notification_id
            WHERE n.target_id=? AND n.type LIKE 'SHIPMENT_%'
            """,Integer.class,order.orderId())).isEqualTo(6);
        var inbox=(List<?>)get("/api/notifications",buyerToken).data().get("notifications");
        var item=(Map<?,?>)inbox.getFirst();
        assertThat(item.get("type")).isEqualTo("SHIPMENT_UPDATED");
        assertThat(item.get("title")).isEqualTo("배송 정보가 변경되었습니다.");
        assertThat(item.get("targetId")).isEqualTo(order.orderId());
        assertThat(request("PATCH","/api/notifications/"+item.get("id")+"/read",buyerToken,null).status()).isEqualTo(200);
    }

    @Test
    void rolledBackShipmentLeavesNeitherInboxNorDelivery() {
        var order=paid();
        request("POST","/api/users/me/device-tokens",buyerToken,Map.of("token","rollback-buyer","platform","IOS"));
        transactions.executeWithoutResult(status -> {
            shipment.save(seller.getId(), order.orderId(), new ShipmentRequest("CJ_LOGISTICS",A), false);
            assertThat(types(order,buyer.getId())).containsExactly("SHIPMENT_REGISTERED");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM push_deliveries",Integer.class)).isEqualTo(1);
            status.setRollbackOnly();
        });
        assertThat(types(order,buyer.getId())).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM push_deliveries",Integer.class)).isZero();
        assertThat(get(path(order,"shipment"),buyerToken).status()).isEqualTo(404);
    }

    @Test
    void withdrawnBuyerGetsNoNewShipmentNotificationOrDelivery() {
        var order=paid();
        assertThat(request("POST","/api/users/me/device-tokens",buyerToken,
            Map.of("token","withdrawn-buyer","platform","IOS")).status()).isEqualTo(200);
        jdbc.update("UPDATE users SET withdrawn_at=CURRENT_TIMESTAMP WHERE id=?",buyer.getId());
        assertThat(ship(order,"POST",A,sellerToken).status()).isEqualTo(200);
        assertThat(types(order,buyer.getId())).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM push_deliveries",Integer.class)).isZero();
    }

    @Test
    void approvedReturnAndImmediateRefundSendOnlyOneCompletionPerParticipant() {
        var returned=paid();
        ship(returned,"POST",A,sellerToken);
        var first=refund(returned);
        var decision=decision(first);
        assertThat(refund(returned).data()).isEqualTo(first.data());
        assertError(post(returned,"refunds/approve",strangerToken,decision),403,"REFUND_004");
        when(toss.cancel(any())).thenReturn(canceled(returned));
        assertThat(post(returned,"refunds/approve",operatorToken,decision).data()).containsEntry("status","COMPLETED");
        assertThat(post(returned,"refunds/approve",operatorToken,decision).status()).isEqualTo(200);
        assertThat(types(returned,buyer.getId())).containsExactly("SHIPMENT_REGISTERED","REFUND_APPROVED","REFUND_COMPLETED");
        assertThat(types(returned,seller.getId())).containsExactly("REFUND_REQUESTED","REFUND_APPROVED","REFUND_COMPLETED");
        assertThat(types(returned,OPERATOR)).isEmpty();

        var unshipped=paid();
        when(toss.cancel(any())).thenReturn(canceled(unshipped));
        assertThat(refund(unshipped).data()).containsEntry("status","COMPLETED");
        assertThat(refund(unshipped).status()).isEqualTo(200);
        assertThat(types(unshipped,buyer.getId())).containsExactly("REFUND_COMPLETED");
        assertThat(types(unshipped,seller.getId())).containsExactly("REFUND_REQUESTED","REFUND_COMPLETED");

        var ordinary=paid();
        cancel(ordinary);
        assertThat(types(ordinary,buyer.getId())).containsExactly("PAYMENT_CANCELED");
        assertThat(types(ordinary,seller.getId())).containsExactly("PAYMENT_CANCELED");
    }

    @ParameterizedTest
    @ValueSource(strings={"reject","withdraw"})
    void deniedOrWithdrawnReturnsAndRepeatedPurchaseConfirmation(String action) {
        var order=paid();
        ship(order,"POST",A,sellerToken);
        var received=refund(order);
        var token=action.equals("reject")?operatorToken:buyerToken;
        var decision=decision(received);
        var expected=action.equals("reject")?"REJECTED":"WITHDRAWN";
        for (int i=0;i<2;i++) {
            assertThat(post(order,"refunds/"+action,token,decision).data()).containsEntry("status",expected);
            assertThat(post(order,"confirm-purchase",buyerToken,null).status()).isEqualTo(200);
        }
        assertThat(types(order,buyer.getId())).containsExactlyElementsOf(action.equals("reject")
            ? List.of("SHIPMENT_REGISTERED","REFUND_REJECTED") : List.of("SHIPMENT_REGISTERED"));
        assertThat(types(order,seller.getId())).containsExactly("REFUND_REQUESTED","REFUND_"+expected,"PURCHASE_CONFIRMED");
    }

    @Test
    void partialCancellationDoesNotAnnounceRefundCompletion() {
        var order=paid();
        when(toss.cancel(any())).thenReturn(new TossPaymentResponse(
            "pg-key-"+order.paymentId(),order.orderId(),12000L,"CANCELED",1000L,null,null,null));
        assertThat(refund(order).data()).containsEntry("status","PROCESSING");
        assertThat(types(order,buyer.getId())).isEmpty();
        assertThat(types(order,seller.getId())).containsExactly("REFUND_REQUESTED");
    }

    @Test
    void duplicateWebhookAndRecoveryRaceNotifyOnlyAfterVerifiedFullCancellation() throws Exception {
        var order=paid();
        when(toss.cancel(any())).thenThrow(new BusinessException(ErrorCode.TOSS_PAYMENTS_REQUEST_FAILED));
        assertThat(refund(order).data()).containsEntry("status","PROCESSING");
        assertThat(types(order,buyer.getId())).isEmpty();
        var attempt=jdbc.queryForObject("SELECT current_attempt_id FROM payments WHERE payment_id=?",String.class,order.paymentId());
        var webhook=new TossPaymentWebhookRequest("PAYMENT_STATUS_CHANGED","2026-10-01T12:00:00+09:00",
            new TossPaymentWebhookRequest.TossPaymentWebhookData("pg-key-"+order.paymentId(),order.orderId(),12000L,0L,"CANCELED"));
        webhooks.receive("trade-notice-one",webhook);
        webhooks.receive("trade-notice-one",webhook);
        webhooks.receive("trade-notice-two",webhook);
        when(toss.getPayment("pg-key-"+order.paymentId())).thenReturn(canceled(order));
        var worker=new PaymentWebhookProcessingService(webhookEvents,attempts,webhooks,recovery,toss,20);
        var start=new CountDownLatch(1);
        try (var pool=Executors.newFixedThreadPool(2)) {
            var normal=pool.submit(()->{start.await(); return recovery.recover(attempt,canceled(order));});
            var incoming=pool.submit(()->{start.await(); worker.processWebhooks(); return null;});
            start.countDown();
            normal.get(15,TimeUnit.SECONDS);
            incoming.get(15,TimeUnit.SECONDS);
        }
        recovery.recover(attempt,canceled(order));
        assertThat(get(path(order,"refunds"),buyerToken).data()).containsEntry("status","COMPLETED");
        assertThat(types(order,buyer.getId())).containsExactly("REFUND_COMPLETED");
        assertThat(types(order,seller.getId())).containsExactly("REFUND_REQUESTED","REFUND_COMPLETED");
    }

    @Test
    void settlementNoticesFollowVerifiedResultsAndGoOnlyToSeller() {
        var order=paid();
        ship(order,"POST",A,sellerToken);
        post(order,"confirm-purchase",buyerToken,null);
        var id=(String)get(path(order,"settlement"),sellerToken).data().get("settlementId");
        when(gateway.request(any())).thenReturn(payout(id,SettlementGateway.Status.PENDING,"payout-1"));
        assertThat(post(order,"settlement",operatorToken,null).data()).containsEntry("status","PROCESSING");
        when(gateway.lookup(any())).thenReturn(payout("SET-MISMATCH",SettlementGateway.Status.FAILED,"payout-1"));
        assertThat(post(order,"settlement",operatorToken,null).data()).containsEntry("status","UNKNOWN");
        assertThat(types(order,seller.getId())).containsExactly("PURCHASE_CONFIRMED");

        when(gateway.lookup(any())).thenReturn(payout(id,SettlementGateway.Status.FAILED,"payout-1"));
        for (int i=0;i<2;i++) {
            assertThat(post(order,"settlement",operatorToken,null).data()).containsEntry("status","FAILED");
        }
        assertThat(types(order,seller.getId())).containsExactly("PURCHASE_CONFIRMED","SETTLEMENT_FAILED");

        when(gateway.lookup(any())).thenReturn(payout(id,SettlementGateway.Status.COMPLETED,"payout-1"));
        for (int i=0;i<2;i++) {
            assertThat(post(order,"settlement",operatorToken,null).data()).containsEntry("status","COMPLETED");
        }
        assertThat(types(order,seller.getId())).containsExactly("PURCHASE_CONFIRMED","SETTLEMENT_FAILED","SETTLEMENT_COMPLETED");
        assertThat(types(order,buyer.getId())).containsExactly("SHIPMENT_REGISTERED");
        assertThat(types(order,OPERATOR)).isEmpty();
    }

    private OrderFixture paid() {
        var order=order(buyer,seller,false);
        approve(order);
        return order;
    }

    private List<String> types(OrderFixture order, long userId) {
        return jdbc.queryForList("""
            SELECT type FROM notifications
            WHERE user_id=? AND target_id=? AND type<>'PAYMENT_SUCCESS' ORDER BY id
            """,String.class,userId,order.orderId());
    }

    private Response ship(OrderFixture order, String method, String number, String token) {
        return request(method,path(order,"shipment"),token,Map.of("carrierCode","CJ_LOGISTICS","trackingNumber",number));
    }

    private Response refund(OrderFixture order) {
        return post(order,"refunds",buyerToken,Map.of("amount",12000,"reason","설명과 다른 상품"));
    }

    private Map<String,Object> decision(Response refund) {
        return Map.of("refundId",refund.data().get("refundId"),"reason","반품 확인","returnReceived",true);
    }

    private Response post(OrderFixture order, String action, String token, Object body) {
        return request("POST",path(order,action),token,body);
    }

    private String path(OrderFixture order, String action) {
        return "/api/orders/"+order.orderId()+"/"+action;
    }

    private TossPaymentResponse canceled(OrderFixture order) {
        return new TossPaymentResponse("pg-key-"+order.paymentId(),order.orderId(),12000L,"CANCELED");
    }

    private String destination() { return "seller-"+seller.getId(); }

    private SettlementGateway.Result payout(String id, SettlementGateway.Status status, String reference) {
        return new SettlementGateway.Result(id,11400L,"KRW",status,reference,
            status==SettlementGateway.Status.COMPLETED?LocalDateTime.now():null,destination());
    }
}
