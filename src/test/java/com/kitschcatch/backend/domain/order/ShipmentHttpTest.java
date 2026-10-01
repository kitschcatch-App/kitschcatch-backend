// 배송 권한·중복 등록·취소 우회 차단을 실제 HTTP로 검증한다.
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
    "spring.datasource.url=jdbc:h2:mem:order-shipment;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password=",
    "spring.jpa.hibernate.ddl-auto=create-drop", "kakao.oauth.native-app-key=test-native-app-key",
    "app.orders.expiration-enabled=false", "app.payments.recovery.enabled=false", "springdoc.api-docs.enabled=true"
})
class ShipmentHttpTest extends ShipmentHttpContract {}
abstract class ShipmentHttpContract extends OrderLifecycleHttpFixture {
    final Map<String,String> shipment=Map.of("carrierCode","CJ_LOGISTICS","trackingNumber","123456789012");
    Response ship(OrderFixture o,String token) {return request("POST","/api/orders/"+o.orderId()+"/shipment",token,shipment);}
    @Test void sellerRegistersAndEditsAndBothPartiesReadTheSameShipment() {
        var o=order(buyer,seller,false); approve(o);
        var first=ship(o,sellerToken); assertThat(first.status()).isEqualTo(200);
        assertThat(ship(o,sellerToken).data()).isEqualTo(first.data());
        assertThat(first.data()).containsEntry("status","SHIPPED").containsEntry("carrierName","CJ대한통운");
        for (var token:List.of(buyerToken,sellerToken)) assertThat(get("/api/orders/"+o.orderId()+"/shipment",token).data()).isEqualTo(first.data());
        var changed=request("PATCH","/api/orders/"+o.orderId()+"/shipment",sellerToken,Map.of("carrierCode","HANJIN","trackingNumber","987654321098"));
        assertThat(changed.status()).isEqualTo(200);
        assertThat(changed.data()).containsEntry("carrierName","한진택배").containsEntry("registeredAt",first.data().get("registeredAt"));
    }
    @Test void buyerCannotShipAndStrangerCannotRead() {
        var o=order(buyer,seller,false); approve(o);
        assertError(ship(o,buyerToken),403,"SHIPMENT_002");
        assertError(ship(o,strangerToken),403,"ORDER_004");
        assertError(ship(o,null),401,"AUTH_004");
        assertError(get("/api/orders/"+o.orderId()+"/shipment",strangerToken),403,"ORDER_004");
        assertError(get("/api/orders/"+o.orderId()+"/shipment",buyerToken),404,"SHIPMENT_001");
    }
    @Test void unpaidOrUncertainPaymentsCannotShip() {
        var o=order(buyer,seller,false);
        assertError(ship(o,sellerToken),409,"ORDER_005"); approve(o);
        jdbc.update("UPDATE payments SET recovery_state='REVIEW_REQUIRED'");
        assertError(ship(o,sellerToken),409,"ORDER_005");
    }
    @Test void shippingBlocksBothOrderAndLegacyPaymentCancellation() {
        var o=order(buyer,seller,false); approve(o); ship(o,sellerToken);
        assertError(request("POST","/api/orders/"+o.orderId()+"/cancel",buyerToken,Map.of("reason","변심")),409,"ORDER_005");
        assertError(request("POST","/api/payments/"+o.paymentId()+"/cancel",buyerToken,null),409,"ORDER_005");
        verify(toss,never()).cancel(any());
    }
    @Test void cancellationInFlightBlocksShipping() {
        var o=order(buyer,seller,false); approve(o);
        when(toss.cancel(any())).thenThrow(new BusinessException(ErrorCode.TOSS_PAYMENTS_REQUEST_FAILED));
        request("POST","/api/orders/"+o.orderId()+"/cancel",buyerToken,Map.of("reason","변심"));
        assertError(ship(o,sellerToken),409,"ORDER_005");
    }
    @Test void conflictingRegistrationAndUpdateWithoutShipmentAreRejected() {
        var o=order(buyer,seller,false); approve(o);
        assertError(request("PATCH","/api/orders/"+o.orderId()+"/shipment",sellerToken,shipment),404,"SHIPMENT_001");
        ship(o,sellerToken);
        assertError(request("POST","/api/orders/"+o.orderId()+"/shipment",sellerToken,Map.of("carrierCode","HANJIN","trackingNumber","987654321098")),409,"ORDER_006");
    }
    @ParameterizedTest @ValueSource(strings={"", "12-345678", "abcd123456", "123", "12345678901234567890123456789012345678901"})
    void invalidTrackingNumberIsRejected(String tracking) {
        var o=order(buyer,seller,false); approve(o);
        assertThat(request("POST","/api/orders/"+o.orderId()+"/shipment",sellerToken,Map.of("carrierCode","CJ_LOGISTICS","trackingNumber",tracking)).status()).isEqualTo(400);
        assertError(get("/api/orders/"+o.orderId()+"/shipment",buyerToken),404,"SHIPMENT_001");
    }
}
