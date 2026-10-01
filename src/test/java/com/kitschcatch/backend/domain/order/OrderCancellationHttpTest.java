// 주문 취소의 소유권·예약 해제·PG 실패와 중복 요청을 실제 HTTP로 검증한다.
package com.kitschcatch.backend.domain.order;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import com.kitschcatch.backend.domain.order.service.PaymentRecoveryService;
import com.kitschcatch.backend.domain.order.toss.TossPaymentResponse;
import com.kitschcatch.backend.global.exception.*;
import java.util.Map;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.datasource.url=jdbc:h2:mem:order-cancel;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password=",
    "spring.jpa.hibernate.ddl-auto=create-drop", "kakao.oauth.native-app-key=test-native-app-key",
    "app.orders.expiration-enabled=false", "app.payments.recovery.enabled=false", "springdoc.api-docs.enabled=true"
})
class OrderCancellationHttpTest extends OrderCancellationHttpContract {}

abstract class OrderCancellationHttpContract extends OrderLifecycleHttpFixture {
    @Autowired PaymentRecoveryService recovery;
    Response cancelOrder(OrderFixture o, String token) {
        return request("POST", "/api/orders/" + o.orderId() + "/cancel", token, Map.of("reason", "단순 변심"));
    }
    @Test void unpaidCancellationReleasesReservationAndExpiresPreparedAttempt() {
        var o=order(buyer,seller,false);
        var first=cancelOrder(o,buyerToken);
        assertThat(first.status()).isEqualTo(200);
        assertThat(first.data()).containsEntry("status","CANCELED").containsEntry("processing",false);
        assertThat(first.data().get("canceledAt")).isNotNull();
        assertThat(cancelOrder(o,buyerToken).data()).isEqualTo(first.data());
        assertThat(jdbc.queryForObject("SELECT product_status FROM posts",String.class)).isEqualTo("ON_SALE");
        assertThat(jdbc.queryForObject("SELECT attempt_status FROM payment_attempts",String.class)).isEqualTo("EXPIRED");
        verifyNoInteractions(toss);
        assertThat(request("POST","/api/payments/"+o.paymentId()+"/confirm",buyerToken,
            Map.of("paymentId",o.paymentId(),"paymentKey","late-key")).status()).isEqualTo(400);
    }
    @Test void onlyBuyerCanCancelAndAuthenticationIsRequired() {
        var o=order(buyer,seller,false);
        for (var token:List.of(sellerToken,strangerToken)) assertError(cancelOrder(o,token),403,"ORDER_004");
        for (var token:new String[]{null,"invalid",tokens.createAccessToken(Long.MAX_VALUE)}) assertError(cancelOrder(o,token),401,"AUTH_004");
        assertError(request("POST","/api/orders/1/cancel",buyerToken,Map.of("reason","취소")),400,"COMMON_002");
        assertError(request("POST","/api/orders/ORD-UNKNOWN/cancel",buyerToken,Map.of("reason","취소")),404,"ORDER_001");
    }
    @Test void paidCancellationUsesOnePgCallAndPreservesReason() {
        var o=order(buyer,seller,false); approve(o);
        when(toss.cancel(any())).thenReturn(new TossPaymentResponse("pg-key-"+o.paymentId(),o.orderId(),12000L,"CANCELED"));
        assertThat(cancelOrder(o,buyerToken).data()).containsEntry("status","CANCELED");
        assertThat(cancelOrder(o,buyerToken).data()).containsEntry("status","CANCELED");
        verify(toss,times(1)).cancel(argThat(r -> r.cancelReason().equals("단순 변심") && r.idempotencyKey()!=null));
        assertThat(jdbc.queryForObject("SELECT cancel_reason FROM payment_attempts WHERE operation='CANCEL'",String.class)).isEqualTo("단순 변심");
    }
    @Test void uncertainPgResultStaysProcessingAndRecoversWithoutSecondCancel() {
        var o=order(buyer,seller,false); approve(o);
        when(toss.cancel(any())).thenThrow(new BusinessException(ErrorCode.TOSS_PAYMENTS_REQUEST_FAILED));
        assertThat(cancelOrder(o,buyerToken).data()).containsEntry("status","PAID").containsEntry("processing",true);
        assertThat(cancelOrder(o,buyerToken).data()).containsEntry("status","PAID").containsEntry("processing",true);
        verify(toss,times(1)).cancel(any());
        String attempt=jdbc.queryForObject("SELECT current_attempt_id FROM payments",String.class);
        recovery.recover(attempt,new TossPaymentResponse("pg-key-"+o.paymentId(),o.orderId(),12000L,"CANCELED"));
        assertThat(cancelOrder(o,buyerToken).data()).containsEntry("status","CANCELED");
    }
    @Test void confirmingPaymentCannotBeLocallyCanceled() {
        var o=order(buyer,seller,false);
        jdbc.update("UPDATE payments SET payment_status='PROCESSING',processing_operation='CONFIRM'");
        assertError(cancelOrder(o,buyerToken),409,"ORDER_005");
        assertThat(jdbc.queryForObject("SELECT product_status FROM posts",String.class)).isEqualTo("RESERVED");
    }
    @ParameterizedTest @ValueSource(strings={"", "   "})
    void invalidReasonDoesNotMutateOrder(String reason) {
        var o=order(buyer,seller,false);
        assertThat(request("POST","/api/orders/"+o.orderId()+"/cancel",buyerToken,Map.of("reason",reason)).status()).isEqualTo(400);
        assertThat(jdbc.queryForObject("SELECT order_status FROM orders",String.class)).isEqualTo("PENDING");
    }
    @Test void unknownConfirmationAttemptBlocksLocalCancellation() {
        var o=order(buyer,seller,false);
        jdbc.update("UPDATE payment_attempts SET attempt_status='UNKNOWN'");
        assertError(cancelOrder(o,buyerToken),409,"ORDER_005");
        assertThat(jdbc.queryForObject("SELECT order_status FROM orders",String.class)).isEqualTo("PENDING");
    }
}
