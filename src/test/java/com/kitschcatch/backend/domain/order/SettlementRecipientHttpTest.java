// 토스 수취인 소유 검증과 연결 권한 및 정산 대상 고정을 실제 HTTP로 검증한다.
package com.kitschcatch.backend.domain.order;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import com.kitschcatch.backend.domain.order.settlement.SettlementGateway;
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "spring.datasource.url=jdbc:h2:mem:settlement-recipient;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver","spring.datasource.username=sa","spring.datasource.password=",
    "spring.jpa.hibernate.ddl-auto=create-drop","kakao.oauth.native-app-key=test-native-app-key",
    "app.orders.expiration-enabled=false","app.payments.recovery.enabled=false","app.settlement.operator-user-ids=900000"
})
class SettlementRecipientHttpTest extends SettlementRecipientHttpContract {}
abstract class SettlementRecipientHttpContract extends OrderLifecycleHttpFixture {
    @MockitoBean SettlementGateway gateway;
    String operatorToken;
    @BeforeEach void operator() {
        jdbc.update("INSERT INTO users(id,nickname,email,auth_provider,provider_user_id,created_at) VALUES(900000,'지급 담당','payout@example.test','KAKAO','payout-operator',CURRENT_TIMESTAMP)");
        operatorToken=tokens.createAccessToken(900000L);reset(gateway);when(gateway.configured()).thenReturn(true);
        when(gateway.recipient(any())).thenAnswer(call->{
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return new SettlementGateway.Recipient(call.getArgument(0),SettlementGateway.sellerReference(seller.getId()),"APPROVED");
        });
    }
    String path() {return "/api/settlement/recipients/"+seller.getId();}
    Response bind(String token,String id) {return request("PUT",path(),token,Map.of("providerSellerId",id));}
    @Test void operatorBindsVerifiedSellerAndOnlyOwnerOrOperatorReads() {
        var first=bind(operatorToken,"seller_123");assertThat(first.status()).isEqualTo(200);
        assertThat(first.data()).containsEntry("providerSellerId","seller_123").containsEntry("refSellerId",SettlementGateway.sellerReference(seller.getId()));
        assertThat(first.data()).doesNotContainKeys("accountNumber","holderName","registeredBy");
        assertThat(bind(operatorToken,"seller_123").data()).isEqualTo(first.data());
        assertThat(get(path(),sellerToken).data()).isEqualTo(first.data());
        assertError(get(path(),buyerToken),403,"SETTLEMENT_004");
        assertError(bind(sellerToken,"seller_123"),403,"SETTLEMENT_004");
        assertError(bind(operatorToken,"seller_456"),409,"SETTLEMENT_008");
    }
    @Test void unverifiedAndForeignRecipientsNeverBind() {
        for(String status:List.of("APPROVAL_REQUIRED","PARTIALLY_APPROVED","KYC_REQUIRED")) {
            when(gateway.recipient(any())).thenReturn(new SettlementGateway.Recipient("seller_123",SettlementGateway.sellerReference(seller.getId()),status));
            assertError(bind(operatorToken,"seller_123"),409,"SETTLEMENT_008");
        }
        when(gateway.recipient(any())).thenReturn(new SettlementGateway.Recipient("seller_123",SettlementGateway.sellerReference(buyer.getId()),"APPROVED"));
        assertError(bind(operatorToken,"seller_123"),409,"SETTLEMENT_008");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM settlement_recipients",Long.class)).isZero();
    }
    @Test void invalidConfigurationAndIdentifiersNeverCallProvider() {
        assertError(bind(operatorToken,"../foreign"),400,"COMMON_001");
        assertError(bind(null,"seller_123"),401,"AUTH_004");
        verify(gateway,never()).recipient(any());
        when(gateway.configured()).thenReturn(false);assertError(bind(operatorToken,"seller_123"),503,"SETTLEMENT_005");
        assertError(get(path(),sellerToken),404,"SETTLEMENT_007");
    }
    @Test void settlementRequiresRecipientAndFreezesTargetAndDefaultFivePercentFee() {
        var o=order(buyer,seller,false);approve(o);
        request("POST","/api/orders/"+o.orderId()+"/shipment",sellerToken,Map.of("carrierCode","CJ_LOGISTICS","trackingNumber","123456789012"));
        request("POST","/api/orders/"+o.orderId()+"/confirm-purchase",buyerToken,null);
        String path="/api/orders/"+o.orderId()+"/settlement";
        assertError(request("POST",path,operatorToken,null),404,"SETTLEMENT_007");
        assertThat(get(path,sellerToken).data()).containsEntry("status","WAITING");bind(operatorToken,"seller_123");
        when(gateway.request(any())).thenAnswer(call->{
            var c=call.getArgument(0,SettlementGateway.Command.class);
            assertThat(c.destination()).isEqualTo("seller_123");assertThat(c.amount()).isEqualTo(11400);assertThat(c.fee()).isEqualTo(600);
            return new SettlementGateway.Result(c.settlementId(),c.amount(),"KRW",SettlementGateway.Status.PENDING,"payout_123",null,c.destination());
        });
        assertThat(request("POST",path,operatorToken,null).data()).containsEntry("status","PROCESSING");
        when(gateway.lookup(any())).thenAnswer(call->{
            var c=call.getArgument(0,SettlementGateway.Command.class);assertThat(c.providerReference()).isEqualTo("payout_123");
            return new SettlementGateway.Result(c.settlementId(),c.amount(),"KRW",SettlementGateway.Status.COMPLETED,"payout_123",java.time.LocalDateTime.now(),"WRONG_SELLER");
        });
        assertThat(request("POST",path,operatorToken,null).data()).containsEntry("status","UNKNOWN");
        verify(gateway,times(1)).request(any());
    }
}
