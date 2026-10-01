// 반품 검수·철회의 권한과 상태 전이 및 PG 취소 경합을 실제 HTTP로 검증한다.
package com.kitschcatch.backend.domain.order;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import com.kitschcatch.backend.domain.order.toss.TossPaymentResponse;
import com.kitschcatch.backend.global.exception.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionSynchronizationManager;
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "spring.datasource.url=jdbc:h2:mem:refund-review;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver","spring.datasource.username=sa","spring.datasource.password=",
    "spring.jpa.hibernate.ddl-auto=create-drop","kakao.oauth.native-app-key=test-native-app-key",
    "app.orders.expiration-enabled=false","app.payments.recovery.enabled=false","app.refunds.operator-user-ids=900000"
})
class RefundReviewHttpTest extends RefundReviewHttpContract {}
abstract class RefundReviewHttpContract extends OrderLifecycleHttpFixture {
    String operatorToken;
    @BeforeEach void operator() {
        jdbc.update("INSERT INTO users(id,nickname,email,auth_provider,provider_user_id,created_at) VALUES(900000,'반품 담당','returns@example.test','KAKAO','return-operator',CURRENT_TIMESTAMP)");
        operatorToken=tokens.createAccessToken(900000L);
    }
    OrderFixture requested() {
        var o=order(buyer,seller,false);approve(o);
        assertThat(request("POST","/api/orders/"+o.orderId()+"/shipment",sellerToken,Map.of("carrierCode","CJ_LOGISTICS","trackingNumber","123456789012")).status()).isEqualTo(200);
        assertThat(request("POST","/api/orders/"+o.orderId()+"/refunds",buyerToken,Map.of("amount",12000,"reason","설명과 다른 상품")).status()).isEqualTo(200);
        return o;
    }
    Map<String,Object> decision(OrderFixture o) {return Map.of("refundId",get("/api/orders/"+o.orderId()+"/refunds",buyerToken).data().get("refundId"),"reason","반품 수령 및 하자 사진 확인","returnReceived",true);}
    Response review(OrderFixture o,String action,String token,Object body) {return request("POST","/api/orders/"+o.orderId()+"/refunds/"+action,token,body);}
    @Test void operatorApprovalExecutesOneFullRefundAndPreservesReturnedGoods() {
        var o=requested(); var decision=decision(o);
        when(toss.cancel(any())).thenAnswer(call->{
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return new TossPaymentResponse("pg-key-"+o.paymentId(),o.orderId(),12000L,"CANCELED");
        });
        var first=review(o,"approve",operatorToken,decision);
        assertThat(first.status()).isEqualTo(200);
        assertThat(first.data()).containsEntry("status","COMPLETED").containsEntry("returnReceived",true).containsEntry("reviewReason",decision.get("reason"));
        assertThat(first.data().get("reviewedAt")).isNotNull();
        assertThat(review(o,"approve",operatorToken,decision).data()).isEqualTo(first.data());
        assertThat(jdbc.queryForObject("SELECT refund_reviewed_by FROM orders",Long.class)).isEqualTo(900000L);
        assertThat(jdbc.queryForObject("SELECT product_status FROM posts",String.class)).isEqualTo("SOLD_OUT");
        verify(toss,times(1)).cancel(any());
        assertError(review(o,"withdraw",buyerToken,decision),409,"ORDER_005");
        assertError(review(o,"reject",operatorToken,decision),409,"ORDER_005");
    }
    @Test void authorityReceiptAndStaleIdAreEnforcedBeforePg() {
        var o=requested(); var decision=decision(o);
        for(String token:List.of(buyerToken,sellerToken,strangerToken)) assertError(review(o,"approve",token,decision),403,"REFUND_004");
        assertError(review(o,"approve",null,decision),401,"AUTH_004");
        var invalid=new HashMap<>(decision);invalid.put("returnReceived",false);
        assertError(review(o,"approve",operatorToken,invalid),400,"REFUND_005");
        invalid.put("returnReceived",true);invalid.put("refundId","REF-"+"0".repeat(32));
        assertError(review(o,"approve",operatorToken,invalid),409,"REFUND_002");
        invalid.put("refundId",decision.get("refundId"));invalid.put("reason"," ");
        assertError(review(o,"reject",operatorToken,invalid),400,"COMMON_001");
        verify(toss,never()).cancel(any());
    }
    @Test void rejectionPreservesReasonAndAllowsConfirmationWithoutReopeningRefund() {
        var o=requested();var decision=decision(o);
        var rejected=review(o,"reject",operatorToken,decision);
        assertThat(rejected.data()).containsEntry("status","REJECTED").containsEntry("returnReceived",false);
        assertThat(review(o,"reject",operatorToken,decision).data()).isEqualTo(rejected.data());
        assertError(review(o,"approve",operatorToken,decision),409,"ORDER_005");
        assertThat(request("POST","/api/orders/"+o.orderId()+"/confirm-purchase",buyerToken,null).status()).isEqualTo(200);
        assertThat(request("POST","/api/orders/"+o.orderId()+"/refunds",buyerToken,Map.of("amount",12000,"reason","설명과 다른 상품")).data()).containsEntry("status","REJECTED");
        verify(toss,never()).cancel(any());
    }
    @Test void buyerWithdrawalPreservesTimeAndAllowsConfirmation() {
        var o=requested();var d=decision(o);
        assertError(review(o,"withdraw",sellerToken,d),403,"ORDER_004");
        assertError(review(o,"withdraw",operatorToken,d),403,"ORDER_004");
        var first=review(o,"withdraw",buyerToken,d);
        assertThat(first.data()).containsEntry("status","WITHDRAWN");assertThat(first.data().get("withdrawnAt")).isNotNull();
        assertThat(review(o,"withdraw",buyerToken,d).data()).isEqualTo(first.data());
        assertError(review(o,"approve",operatorToken,d),409,"ORDER_005");
        assertThat(request("POST","/api/orders/"+o.orderId()+"/confirm-purchase",buyerToken,null).status()).isEqualTo(200);
    }
    @Test void failedPgApprovalStaysProcessingAndCannotBeWithdrawnOrResent() {
        var o=requested();var d=decision(o);
        when(toss.cancel(any())).thenThrow(new BusinessException(ErrorCode.TOSS_PAYMENTS_REQUEST_FAILED));
        assertThat(review(o,"approve",operatorToken,d).data()).containsEntry("status","PROCESSING");
        assertThat(review(o,"approve",operatorToken,d).data()).containsEntry("status","PROCESSING");
        assertError(review(o,"withdraw",buyerToken,d),409,"ORDER_005");
        verify(toss,times(1)).cancel(any());
    }
    @Test void approvalAndWithdrawalAreSerialized() throws Exception {
        var o=requested();var d=decision(o);var start=new CountDownLatch(1);
        when(toss.cancel(any())).thenReturn(new TossPaymentResponse("pg-key-"+o.paymentId(),o.orderId(),12000L,"CANCELED"));
        try(var pool=Executors.newFixedThreadPool(2)) {
            var approval=pool.submit(()->{start.await();return review(o,"approve",operatorToken,d);});
            var withdrawal=pool.submit(()->{start.await();return review(o,"withdraw",buyerToken,d);});
            start.countDown();assertThat(List.of(approval.get(10,TimeUnit.SECONDS).status(),withdrawal.get(10,TimeUnit.SECONDS).status())).containsExactlyInAnyOrder(200,409);
        }
        verify(toss,atMostOnce()).cancel(any());
    }
    @Test void reviewRequiredPaymentCannotBeApprovedOrWithdrawn() {
        var o=requested();var d=decision(o);jdbc.update("UPDATE payments SET recovery_state='REVIEW_REQUIRED'");
        assertError(review(o,"approve",operatorToken,d),409,"ORDER_005");
        assertError(review(o,"withdraw",buyerToken,d),409,"ORDER_005");
        assertThat(get("/api/orders/"+o.orderId()+"/refunds",operatorToken).data()).containsEntry("status","REQUESTED");
    }
}
