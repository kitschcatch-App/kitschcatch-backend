// 실제 기본 정산 구성은 수수료 값만으로 지급을 활성화하지 않음을 검증한다.
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
    "spring.datasource.url=jdbc:h2:mem:order-settlement-disabled;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password=",
    "spring.jpa.hibernate.ddl-auto=create-drop", "kakao.oauth.native-app-key=test-native-app-key",
    "app.orders.expiration-enabled=false", "app.payments.recovery.enabled=false", "springdoc.api-docs.enabled=true", "app.settlement.operator-user-ids=900000", "app.settlement.fee-basis-points=500"
})
class SettlementUnavailableHttpTest extends OrderLifecycleHttpFixture {
    @Autowired com.kitschcatch.backend.domain.order.settlement.SettlementGateway gateway;
    @Test void realDefaultGatewayCannotBeEnabledWithOnlyEnvironmentValues() {
        jdbc.update("INSERT INTO users(id,nickname,email,auth_provider,provider_user_id,created_at) VALUES(900000,'담당자','ops@example.test','KAKAO','ops',CURRENT_TIMESTAMP)");
        assertThat(gateway.configured()).isFalse();
        var o=order(buyer,seller,false);approve(o);
        request("POST","/api/orders/"+o.orderId()+"/shipment",sellerToken,Map.of("carrierCode","CJ_LOGISTICS","trackingNumber","123456789012"));
        request("POST","/api/orders/"+o.orderId()+"/confirm-purchase",buyerToken,null);
        assertError(request("POST","/api/orders/"+o.orderId()+"/settlement",tokens.createAccessToken(900000L),null),503,"SETTLEMENT_005");
        assertThat(get("/api/orders/"+o.orderId()+"/settlement",sellerToken).data()).containsEntry("status","WAITING").containsEntry("amount",null);
    }
}
