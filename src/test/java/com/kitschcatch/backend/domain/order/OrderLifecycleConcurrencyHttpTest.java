// 경쟁 요청에서 주문 잠금과 PG 호출 분리가 유지되는지 검증한다.
package com.kitschcatch.backend.domain.order;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import com.kitschcatch.backend.domain.order.service.PaymentRecoveryService;
import com.kitschcatch.backend.domain.order.toss.TossPaymentResponse;
import com.kitschcatch.backend.global.exception.*;
import java.util.Map;
import java.util.concurrent.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.datasource.url=jdbc:h2:mem:order-race;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password=",
    "spring.jpa.hibernate.ddl-auto=create-drop", "kakao.oauth.native-app-key=test-native-app-key",
    "app.orders.expiration-enabled=false", "app.payments.recovery.enabled=false", "springdoc.api-docs.enabled=true"
})
class OrderLifecycleConcurrencyHttpTest extends OrderLifecycleConcurrencyHttpContract {}
abstract class OrderLifecycleConcurrencyHttpContract extends OrderLifecycleHttpFixture {
    Response cancelOrder(OrderFixture o) {return request("POST","/api/orders/"+o.orderId()+"/cancel",buyerToken,Map.of("reason","취소"));}
    @Test void duplicateCancelDoesNotCallPgTwiceAndShippingCannotPassWhilePgIsRunning() throws Exception {
        var o=order(buyer,seller,false); approve(o);
        var entered=new CountDownLatch(1); var finish=new CountDownLatch(1);
        when(toss.cancel(any())).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            entered.countDown();
            assertThat(finish.await(10,TimeUnit.SECONDS)).isTrue();
            return new TossPaymentResponse("pg-key-"+o.paymentId(),o.orderId(),12000L,"CANCELED");
        });
        try(var pool=Executors.newFixedThreadPool(2)) {
            var first=pool.submit(() -> cancelOrder(o));
            try {
                assertThat(entered.await(10,TimeUnit.SECONDS)).isTrue();
                assertThat(cancelOrder(o).data()).containsEntry("processing",true);
                assertError(request("POST","/api/orders/"+o.orderId()+"/shipment",sellerToken,
                    Map.of("carrierCode","CJ_LOGISTICS","trackingNumber","123456789012")),409,"ORDER_005");
            } finally { finish.countDown(); }
            assertThat(first.get(10,TimeUnit.SECONDS).data()).containsEntry("status","CANCELED");
        }
        verify(toss,times(1)).cancel(any());
    }
    @Test void simultaneousShipAndCancelHaveOnlyOneWinner() throws Exception {
        var o=order(buyer,seller,false); approve(o);
        when(toss.cancel(any())).thenReturn(new TossPaymentResponse("pg-key-"+o.paymentId(),o.orderId(),12000L,"CANCELED"));
        var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var cancel=pool.submit(() -> { start.await(); return cancelOrder(o); });
            var shipment=pool.submit(() -> {start.await();return request("POST","/api/orders/"+o.orderId()+"/shipment",sellerToken,
                Map.of("carrierCode","CJ_LOGISTICS","trackingNumber","123456789012"));});
            start.countDown();
            assertThat(List.of(cancel.get(10,TimeUnit.SECONDS).status(),shipment.get(10,TimeUnit.SECONDS).status())).containsExactlyInAnyOrder(200,409);
        }
        var row=jdbc.queryForMap("SELECT order_status,shipment_tracking_number FROM orders");
        if(row.get("shipment_tracking_number")!=null) {
            assertThat(row).containsEntry("order_status","PAID"); verify(toss,never()).cancel(any());
        } else assertThat(row).containsEntry("order_status","CANCELED");
    }
}
