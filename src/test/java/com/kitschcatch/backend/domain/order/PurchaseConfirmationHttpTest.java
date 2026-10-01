// 구매 확정 권한과 늦은 PG 취소 및 조회 계약을 검증한다.
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
    "spring.datasource.url=jdbc:h2:mem:order-confirmation;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password=",
    "spring.jpa.hibernate.ddl-auto=create-drop", "kakao.oauth.native-app-key=test-native-app-key",
    "app.orders.expiration-enabled=false", "app.payments.recovery.enabled=false", "springdoc.api-docs.enabled=true"
})
class PurchaseConfirmationHttpTest extends PurchaseConfirmationHttpContract {}
abstract class PurchaseConfirmationHttpContract extends OrderLifecycleHttpFixture {
    @Autowired PaymentRecoveryService recovery;
    void ship(OrderFixture o) { assertThat(request("POST","/api/orders/"+o.orderId()+"/shipment",sellerToken,
        Map.of("carrierCode","CJ_LOGISTICS","trackingNumber","123456789012")).status()).isEqualTo(200); }
    Response confirm(OrderFixture o,String token) { return request("POST","/api/orders/"+o.orderId()+"/confirm-purchase",token,null); }
    @Test void buyerConfirmsOnceAndHistoryIncludesTheNewStatusAndShipment() {
        var o=order(buyer,seller,false); approve(o); ship(o);
        var first=confirm(o,buyerToken); assertThat(first.status()).isEqualTo(200);
        assertThat(first.data()).containsEntry("status","PURCHASE_CONFIRMED");
        assertThat(confirm(o,buyerToken).data()).isEqualTo(first.data());
        assertThat(get("/api/users/me/purchase-orders?status=PURCHASE_CONFIRMED",buyerToken).ids()).containsExactly(o.orderId());
        var detail=get("/api/orders/"+o.orderId(),sellerToken).data();
        assertThat(detail).containsEntry("confirmedAt",first.data().get("confirmedAt"));
        assertThat(child(detail,"shipment")).containsEntry("status","SHIPPED");
        assertThat(jdbc.queryForObject("SELECT product_status FROM posts",String.class)).isEqualTo("SOLD_OUT");
    }
    @Test void onlyBuyerOfPaidShippedOrderCanConfirm() {
        var o=order(buyer,seller,false);
        assertError(confirm(o,buyerToken),409,"ORDER_005"); approve(o);
        assertError(confirm(o,buyerToken),409,"ORDER_005"); ship(o);
        for(var token:List.of(sellerToken,strangerToken)) assertError(confirm(o,token),403,"ORDER_004");
        assertError(confirm(o,null),401,"AUTH_004");
    }
    @Test void pendingRefundAndUncertainPaymentBlockConfirmation() {
        var o=order(buyer,seller,false); approve(o); ship(o);
        jdbc.update("UPDATE payments SET recovery_state='REVIEW_REQUIRED'");
        assertError(confirm(o,buyerToken),409,"ORDER_005");
        jdbc.update("UPDATE payments SET recovery_state='NONE'");
        jdbc.update("UPDATE orders SET refund_id='REF-PENDING',refund_amount=12000,refund_reason='반품',refund_status='REQUESTED',refund_requested_at=CURRENT_TIMESTAMP");
        assertError(confirm(o,buyerToken),409,"ORDER_005");
    }
    @Test void confirmedOrderRejectsCancellationAndShipmentChanges() {
        var o=order(buyer,seller,false); approve(o); ship(o); confirm(o,buyerToken);
        assertError(request("POST","/api/orders/"+o.orderId()+"/cancel",buyerToken,Map.of("reason","취소")),409,"ORDER_005");
        assertError(request("POST","/api/payments/"+o.paymentId()+"/cancel",buyerToken,null),409,"ORDER_005");
        assertError(request("PATCH","/api/orders/"+o.orderId()+"/shipment",sellerToken,Map.of("carrierCode","HANJIN","trackingNumber","987654321098")),409,"ORDER_005");
    }
    @Test void lateExternalCancellationAfterConfirmationRequiresReviewWithoutReselling() {
        var o=order(buyer,seller,false); approve(o); ship(o); confirm(o,buyerToken);
        String attempt=jdbc.queryForObject("SELECT current_attempt_id FROM payments",String.class);
        recovery.recover(attempt,new TossPaymentResponse("pg-key-"+o.paymentId(),o.orderId(),12000L,"CANCELED"));
        assertThat(jdbc.queryForObject("SELECT recovery_state FROM payments",String.class)).isEqualTo("REVIEW_REQUIRED");
        recovery.recover(attempt,new TossPaymentResponse("pg-key-"+o.paymentId(),o.orderId(),12000L,"DONE"));
        assertThat(jdbc.queryForObject("SELECT recovery_state FROM payments",String.class)).isEqualTo("REVIEW_REQUIRED");
        assertThat(jdbc.queryForObject("SELECT order_status FROM orders",String.class)).isEqualTo("PURCHASE_CONFIRMED");
        assertThat(jdbc.queryForObject("SELECT product_status FROM posts",String.class)).isEqualTo("SOLD_OUT");
    }
    @Test void mismatchedPgResponseCannotCancelAnAlreadyApprovedPayment() {
        var o=order(buyer,seller,false); approve(o);
        String attempt=jdbc.queryForObject("SELECT current_attempt_id FROM payments",String.class);
        recovery.recover(attempt,new TossPaymentResponse("wrong-key",o.orderId(),12000L,"CANCELED"));
        assertThat(jdbc.queryForObject("SELECT order_status FROM orders",String.class)).isEqualTo("PAID");
        assertThat(jdbc.queryForObject("SELECT recovery_state FROM payments",String.class)).isEqualTo("REVIEW_REQUIRED");
    }
}
