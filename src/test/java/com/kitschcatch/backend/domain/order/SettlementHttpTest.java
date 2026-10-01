// 지급 연동 대역으로 내부 권한·금액·멱등 실행·결과 미확정 복구를 검증한다.
package com.kitschcatch.backend.domain.order;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import com.kitschcatch.backend.domain.order.service.PaymentRecoveryService;
import com.kitschcatch.backend.domain.order.toss.TossPaymentResponse;
import com.kitschcatch.backend.global.exception.*;
import java.util.Map;
import java.time.LocalDateTime;
import java.util.concurrent.*;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import com.kitschcatch.backend.domain.order.settlement.SettlementGateway;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.datasource.url=jdbc:h2:mem:order-settlement;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password=",
    "spring.jpa.hibernate.ddl-auto=create-drop", "kakao.oauth.native-app-key=test-native-app-key",
    "app.orders.expiration-enabled=false", "app.payments.recovery.enabled=false", "springdoc.api-docs.enabled=true", "app.settlement.operator-user-ids=900000", "app.settlement.fee-basis-points=500"
})
class SettlementHttpTest extends SettlementHttpContract {}
abstract class SettlementHttpContract extends OrderLifecycleHttpFixture {
    @MockitoBean SettlementGateway gateway;
    String operatorToken;
    @BeforeEach void operator() {
        jdbc.update("INSERT INTO users(id,nickname,email,auth_provider,provider_user_id,created_at) VALUES(900000,'정산 담당','settlement@example.test','KAKAO','settlement-operator',CURRENT_TIMESTAMP)");
        operatorToken=tokens.createAccessToken(900000L);
        reset(gateway);when(gateway.configured()).thenReturn(true);
        jdbc.update("INSERT INTO settlement_recipients(seller_id,provider_seller_id,registered_by,verified_at) VALUES(?,?,900000,CURRENT_TIMESTAMP)",seller.getId(),"seller-"+seller.getId());
    }
    OrderFixture confirmedOrder() {
        var o=order(buyer,seller,false);approve(o);
        assertThat(request("POST","/api/orders/"+o.orderId()+"/shipment",sellerToken,
            Map.of("carrierCode","CJ_LOGISTICS","trackingNumber","123456789012")).status()).isEqualTo(200);
        assertThat(request("POST","/api/orders/"+o.orderId()+"/confirm-purchase",buyerToken,null).status()).isEqualTo(200);
        return o;
    }
    Response execute(OrderFixture o,String token) {return request("POST","/api/orders/"+o.orderId()+"/settlement",token,null);}
    SettlementGateway.Result complete(String id) {return new SettlementGateway.Result(id,11400L,"KRW",SettlementGateway.Status.COMPLETED,"provider-123",LocalDateTime.of(2026,10,1,18,0),"seller-"+seller.getId());}
    @Test void confirmedOrderWaitsUntilProviderConfirmsActualPayout() {
        var o=confirmedOrder();
        var waiting=get("/api/orders/"+o.orderId()+"/settlement",sellerToken);
        assertThat(waiting.data()).containsEntry("status","WAITING").containsEntry("amount",null).containsEntry("fee",null);
        when(gateway.request(any())).thenAnswer(call->{
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            var c=call.getArgument(0,SettlementGateway.Command.class);
            assertThat(c.amount()).isEqualTo(11400);assertThat(c.fee()).isEqualTo(600);assertThat(c.sellerId()).isEqualTo(seller.getId());
            return complete(c.settlementId());
        });
        var completed=execute(o,operatorToken);assertThat(completed.status()).isEqualTo(200);
        assertThat(completed.data()).containsEntry("status","COMPLETED").containsEntry("amount",11400).containsEntry("fee",600);
        assertThat(execute(o,operatorToken).data()).isEqualTo(completed.data());
        for(var token:List.of(buyerToken,sellerToken,operatorToken)) assertThat(get("/api/orders/"+o.orderId()+"/settlement",token).data()).isEqualTo(completed.data());
        verify(gateway,times(1)).request(any());verify(gateway,never()).lookup(any());
    }
    @Test void onlyConfiguredInternalUsersCanExecuteAndStrangersCannotRead() {
        var o=confirmedOrder();
        for(var token:List.of(buyerToken,sellerToken,strangerToken)) assertError(execute(o,token),403,"SETTLEMENT_004");
        assertError(execute(o,null),401,"AUTH_004");
        assertError(get("/api/orders/"+o.orderId()+"/settlement",strangerToken),403,"ORDER_004");
        verify(gateway,never()).request(any());
    }
    @Test void paymentReviewAndUnconfirmedOrdersCannotBeSettled() {
        var o=order(buyer,seller,false);approve(o);
        assertError(execute(o,operatorToken),409,"SETTLEMENT_001");
        assertError(get("/api/orders/"+o.orderId()+"/settlement",sellerToken),404,"SETTLEMENT_003");
        var confirmed=confirmedOrder();jdbc.update("UPDATE payments SET recovery_state='REVIEW_REQUIRED'");
        assertError(execute(confirmed,operatorToken),409,"SETTLEMENT_001");
    }
    @Test void preflightFailureAllowsRetryWithTheSameIdentifierAndSnapshot() {
        var o=confirmedOrder();
        when(gateway.request(any())).thenThrow(new com.kitschcatch.backend.domain.order.settlement.SettlementNotSubmittedException(ErrorCode.SETTLEMENT_PRECHECK_FAILED));
        assertError(execute(o,operatorToken),409,"SETTLEMENT_009");
        var first=get("/api/orders/"+o.orderId()+"/settlement",sellerToken).data();
        assertThat(first).containsEntry("status","WAITING").containsEntry("fee",600);
        doAnswer(call->{var c=call.getArgument(0,SettlementGateway.Command.class);assertThat(c.settlementId()).isEqualTo(first.get("settlementId"));return complete(c.settlementId());}).when(gateway).request(any());
        assertThat(execute(o,operatorToken).data()).containsEntry("status","COMPLETED").containsEntry("requestedAt",first.get("requestedAt"));
        verify(gateway,times(2)).request(any());verify(gateway,never()).lookup(any());
    }
    @Test void missingProviderNeverPretendsSettlementCompleted() {
        var o=confirmedOrder();when(gateway.configured()).thenReturn(false);
        assertError(execute(o,operatorToken),503,"SETTLEMENT_005");
        assertThat(get("/api/orders/"+o.orderId()+"/settlement",sellerToken).data()).containsEntry("status","WAITING").containsEntry("amount",null);
        verify(gateway,never()).request(any());
    }
    @Test void uncertainRequestIsRecoveredByLookupWithNoSecondPayout() {
        var o=confirmedOrder();
        String id=(String)get("/api/orders/"+o.orderId()+"/settlement",sellerToken).data().get("settlementId");
        when(gateway.request(any())).thenThrow(new IllegalStateException("timeout"));
        assertError(execute(o,operatorToken),502,"SETTLEMENT_006");
        assertThat(get("/api/orders/"+o.orderId()+"/settlement",sellerToken).data()).containsEntry("status","UNKNOWN");
        when(gateway.lookup(any())).thenReturn(complete(id));
        assertThat(execute(o,operatorToken).data()).containsEntry("status","COMPLETED");
        verify(gateway,times(1)).request(any());verify(gateway,times(1)).lookup(any());
    }
    @Test void mismatchedPayoutEvidenceCannotCompleteSettlement() {
        var o=confirmedOrder();
        when(gateway.request(any())).thenReturn(complete("SET-WRONG"));
        assertThat(execute(o,operatorToken).data()).containsEntry("status","UNKNOWN");
        String id=(String)get("/api/orders/"+o.orderId()+"/settlement",sellerToken).data().get("settlementId");
        when(gateway.lookup(any())).thenReturn(new SettlementGateway.Result(id,11401L,"KRW",SettlementGateway.Status.COMPLETED,"ref",LocalDateTime.now(),"seller-"+seller.getId()));
        assertThat(execute(o,operatorToken).data()).containsEntry("status","UNKNOWN");
        when(gateway.lookup(any())).thenReturn(new SettlementGateway.Result(id,11400L,"KRW",SettlementGateway.Status.COMPLETED,null,null,"seller-"+seller.getId()));
        assertThat(execute(o,operatorToken).data()).containsEntry("status","UNKNOWN");
        verify(gateway,times(1)).request(any());
    }
    @Test void failedPayoutIsNotResubmittedAutomatically() {
        var o=confirmedOrder();String id=(String)get("/api/orders/"+o.orderId()+"/settlement",sellerToken).data().get("settlementId");
        var result=new SettlementGateway.Result(id,11400L,"KRW",SettlementGateway.Status.FAILED,null,null,"seller-"+seller.getId());
        when(gateway.request(any())).thenReturn(result);when(gateway.lookup(any())).thenReturn(result);
        assertThat(execute(o,operatorToken).data()).containsEntry("status","FAILED");
        assertThat(execute(o,operatorToken).data()).containsEntry("status","FAILED");
        verify(gateway,times(1)).request(any());
    }
    @Test void concurrentExecutionMakesOnePayoutRequest() throws Exception {
        var o=confirmedOrder();String id=(String)get("/api/orders/"+o.orderId()+"/settlement",sellerToken).data().get("settlementId");
        var entered=new CountDownLatch(1);var finish=new CountDownLatch(1);
        when(gateway.request(any())).thenAnswer(call->{entered.countDown();assertThat(finish.await(10,TimeUnit.SECONDS)).isTrue();return complete(id);});
        when(gateway.lookup(any())).thenReturn(new SettlementGateway.Result(id,11400L,"KRW",SettlementGateway.Status.PENDING,null,null,"seller-"+seller.getId()));
        try(var pool=Executors.newFixedThreadPool(2)) {
            var first=pool.submit(()->execute(o,operatorToken));
            try { assertThat(entered.await(10,TimeUnit.SECONDS)).isTrue();assertThat(execute(o,operatorToken).data()).containsEntry("status","PROCESSING"); }
            finally {finish.countDown();}
            assertThat(first.get(10,TimeUnit.SECONDS).data()).containsEntry("status","COMPLETED");
        }
        verify(gateway,times(1)).request(any());
    }
    @Test @SuppressWarnings("unchecked") void swaggerDescribesAllFourteenAuthenticatedEndpoints() {
        var paths=(Map<String,Map<String,Map<String,Object>>>)get("/v3/api-docs",null).body().get("paths");
        for(var suffix:List.of("cancel","shipment","refunds","confirm-purchase","settlement","refunds/approve","refunds/reject","refunds/withdraw")) {
            var methods=paths.get("/api/orders/{orderId}/"+suffix);
            for(var m:methods.values()) assertThat(m.get("security").toString()).contains("bearerAuth");
        }
        for(var method:paths.get("/api/settlement/recipients/{sellerId}").values()) assertThat(method.get("security").toString()).contains("bearerAuth");
    }
}
