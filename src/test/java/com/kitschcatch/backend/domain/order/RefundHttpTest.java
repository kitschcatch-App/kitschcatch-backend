// 전액 환불과 배송 후 접수·PG 지연·권한·구매 확정 경합을 검증한다.
package com.kitschcatch.backend.domain.order;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import com.kitschcatch.backend.domain.order.service.PaymentRecoveryService;
import com.kitschcatch.backend.domain.order.toss.TossPaymentResponse;
import com.kitschcatch.backend.global.exception.*;
import java.util.Map;
import java.util.concurrent.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.datasource.url=jdbc:h2:mem:order-refund;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password=",
    "spring.jpa.hibernate.ddl-auto=create-drop", "kakao.oauth.native-app-key=test-native-app-key",
    "app.orders.expiration-enabled=false", "app.payments.recovery.enabled=false", "springdoc.api-docs.enabled=true"
})
class RefundHttpTest extends RefundHttpContract {}
abstract class RefundHttpContract extends OrderLifecycleHttpFixture {
    @Autowired PaymentRecoveryService recovery;
    Response refund(OrderFixture o,long amount,String reason,String token) {
        return request("POST","/api/orders/"+o.orderId()+"/refunds",token,Map.of("amount",amount,"reason",reason));
    }
    void ship(OrderFixture o) {assertThat(request("POST","/api/orders/"+o.orderId()+"/shipment",sellerToken,
        Map.of("carrierCode","CJ_LOGISTICS","trackingNumber","123456789012")).status()).isEqualTo(200);}
    @Test void unshippedFullRefundCompletesOnlyAfterPgVerification() {
        var o=order(buyer,seller,false);approve(o);
        when(toss.cancel(any())).thenReturn(new TossPaymentResponse("pg-key-"+o.paymentId(),o.orderId(),12000L,"CANCELED"));
        var first=refund(o,12000,"상품 설명과 다름",buyerToken);
        assertThat(first.status()).isEqualTo(200);assertThat(first.data()).containsEntry("status","COMPLETED");
        assertThat(first.data().get("completedAt")).isNotNull();
        assertThat(refund(o,12000,"상품 설명과 다름",buyerToken).data()).isEqualTo(first.data());
        assertError(refund(o,12000,"다른 사유",buyerToken),409,"REFUND_002");
        assertThat(get("/api/orders/"+o.orderId(),buyerToken).data()).containsEntry("status","REFUNDED");
        assertThat(get("/api/users/me/purchase-orders?status=REFUNDED",buyerToken).ids()).containsExactly(o.orderId());
        assertThat(jdbc.queryForObject("SELECT product_status FROM posts",String.class)).isEqualTo("ON_SALE");
        verify(toss,times(1)).cancel(argThat(r->r.cancelReason().equals("상품 설명과 다름")));
    }
    @Test void shippedRefundIsRequestedWithoutAutomaticallyMovingMoney() {
        var o=order(buyer,seller,false);approve(o);ship(o);
        var first=refund(o,12000,"반품 요청",buyerToken);
        assertThat(first.data()).containsEntry("status","REQUESTED");
        assertThat(refund(o,12000,"반품 요청",buyerToken).data()).isEqualTo(first.data());
        assertError(request("POST","/api/orders/"+o.orderId()+"/confirm-purchase",buyerToken,null),409,"ORDER_005");
        assertError(request("POST","/api/payments/"+o.paymentId()+"/cancel",buyerToken,null),409,"ORDER_005");
        assertError(request("PATCH","/api/orders/"+o.orderId()+"/shipment",sellerToken,
            Map.of("carrierCode","HANJIN","trackingNumber","987654321098")),409,"ORDER_005");
        verify(toss,never()).cancel(any());
    }
    @Test void externalRefundCompletionOfShippedOrderDoesNotRelistGoods() {
        var o=order(buyer,seller,false);approve(o);ship(o);refund(o,12000,"반품 요청",buyerToken);
        String attempt=jdbc.queryForObject("SELECT current_attempt_id FROM payments",String.class);
        recovery.recover(attempt,new TossPaymentResponse("pg-key-"+o.paymentId(),o.orderId(),12000L,"CANCELED"));
        assertThat(get("/api/orders/"+o.orderId()+"/refunds",buyerToken).data()).containsEntry("status","COMPLETED");
        assertThat(jdbc.queryForObject("SELECT product_status FROM posts",String.class)).isEqualTo("SOLD_OUT");
    }
    @Test void onlyBuyerMayRequestAndOnlyParticipantsMayRead() {
        var o=order(buyer,seller,false);approve(o);
        for(var token:List.of(sellerToken,strangerToken)) assertError(refund(o,12000,"환불",token),403,"ORDER_004");
        assertError(refund(o,12000,"환불",null),401,"AUTH_004");
        assertError(get("/api/orders/"+o.orderId()+"/refunds",buyerToken),404,"REFUND_003");
        assertError(get("/api/orders/"+o.orderId()+"/refunds",strangerToken),403,"ORDER_004");
    }
    @Test void partialAmountsAndUnavailableOrderStatesAreRejected() {
        var o=order(buyer,seller,false);
        assertError(refund(o,12000,"환불",buyerToken),409,"ORDER_005");approve(o);
        for(long amount:List.of(1L,11999L,12001L,Long.MAX_VALUE)) assertError(refund(o,amount,"환불",buyerToken),400,"REFUND_001");
        ship(o);request("POST","/api/orders/"+o.orderId()+"/confirm-purchase",buyerToken,null);
        assertError(refund(o,12000,"환불",buyerToken),409,"ORDER_005");
    }
    @Test void uncertainPgRefundRecoversAndNeverRetriesTheOutgoingRequest() {
        var o=order(buyer,seller,false);approve(o);
        when(toss.cancel(any())).thenThrow(new BusinessException(ErrorCode.TOSS_PAYMENTS_REQUEST_FAILED));
        assertThat(refund(o,12000,"환불",buyerToken).data()).containsEntry("status","PROCESSING");
        assertThat(refund(o,12000,"환불",buyerToken).data()).containsEntry("status","PROCESSING");
        verify(toss,times(1)).cancel(any());
        String attempt=jdbc.queryForObject("SELECT current_attempt_id FROM payments",String.class);
        recovery.recover(attempt,new TossPaymentResponse("pg-key-"+o.paymentId(),o.orderId(),12000L,"CANCELED"));
        assertThat(get("/api/orders/"+o.orderId()+"/refunds",sellerToken).data()).containsEntry("status","COMPLETED");
    }
    @Test void partialPgCancellationDoesNotMarkFullRefundCompleted() {
        var o=order(buyer,seller,false);approve(o);
        when(toss.cancel(any())).thenReturn(new TossPaymentResponse("pg-key-"+o.paymentId(),o.orderId(),12000L,"CANCELED",1000L,null,null,null));
        assertThat(refund(o,12000,"환불",buyerToken).data()).containsEntry("status","PROCESSING");
        assertThat(jdbc.queryForObject("SELECT recovery_state FROM payments",String.class)).isEqualTo("REVIEW_REQUIRED");
        assertThat(jdbc.queryForObject("SELECT order_status FROM orders",String.class)).isEqualTo("PAID");
    }
    @Test void concurrentRefundAndConfirmationHaveOneWinner() throws Exception {
        var o=order(buyer,seller,false);approve(o);ship(o);var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var refund=pool.submit(()->{start.await();return refund(o,12000,"반품",buyerToken);});
            var confirm=pool.submit(()->{start.await();return request("POST","/api/orders/"+o.orderId()+"/confirm-purchase",buyerToken,null);});
            start.countDown();assertThat(List.of(refund.get(10,TimeUnit.SECONDS).status(),confirm.get(10,TimeUnit.SECONDS).status())).containsExactlyInAnyOrder(200,409);
        }
    }
}
