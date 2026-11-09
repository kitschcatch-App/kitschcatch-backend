// 거래 알림 생성을 꺼도 기존 결제 알림을 유지하고 새 알림을 읽는지 검증한다.
package com.kitschcatch.backend.domain.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.kitschcatch.backend.domain.order.toss.TossPaymentResponse;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.datasource.url=jdbc:h2:mem:trade-notice-compat;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password=",
    "spring.jpa.hibernate.ddl-auto=create-drop", "kakao.oauth.native-app-key=test-native-app-key",
    "app.orders.expiration-enabled=false", "app.payments.recovery.enabled=false",
    "app.notifications.trade-enabled=false"
})
class TradeNotificationCompatibilityHttpTest extends OrderLifecycleHttpFixture {
    @Test
    void disabledEmissionKeepsLegacyRefundNoticeAndCanReadAlreadyStoredTradeType() {
        var order=order(buyer,seller,false);
        approve(order);
        when(toss.cancel(any())).thenReturn(
            new TossPaymentResponse("pg-key-"+order.paymentId(),order.orderId(),12000L,"CANCELED"));
        assertThat(request("POST","/api/orders/"+order.orderId()+"/refunds",buyerToken,
            Map.of("amount",12000,"reason","환불 요청")).data()).containsEntry("status","COMPLETED");
        assertThat(jdbc.queryForList("SELECT type FROM notifications ORDER BY id",String.class))
            .containsExactly("PAYMENT_SUCCESS","PAYMENT_SUCCESS","PAYMENT_CANCELED","PAYMENT_CANCELED");

        jdbc.update("""
            INSERT INTO notifications(user_id,event_key,type,title,target_id,created_at)
            VALUES(?,'existing-trade-notice','REFUND_COMPLETED','기존 환불 완료',?,CURRENT_TIMESTAMP)
            """,buyer.getId(),order.orderId());
        var inbox=get("/api/notifications",buyerToken);
        assertThat(inbox.status()).isEqualTo(200);
        var items=(List<?>)inbox.data().get("notifications");
        assertThat(items.stream().map(item->((Map<?,?>)item).get("type").toString()))
            .containsExactly("REFUND_COMPLETED","PAYMENT_CANCELED","PAYMENT_SUCCESS");
    }
}
