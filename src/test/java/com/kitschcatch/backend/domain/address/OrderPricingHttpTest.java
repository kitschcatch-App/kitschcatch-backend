// 실제 인증·견적·결제·환불·정산 금액과 PostgreSQL 불변식을 검증한다.
package com.kitschcatch.backend.domain.address;

import static com.kitschcatch.backend.domain.order.toss.TossPaymentFixtures.cardApproval;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.kitschcatch.backend.domain.post.entity.*;
import com.kitschcatch.backend.domain.order.entity.*;
import com.kitschcatch.backend.domain.order.repository.*;
import com.kitschcatch.backend.domain.order.service.OrderAccessService;
import com.kitschcatch.backend.domain.order.settlement.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import java.util.*;
import java.nio.file.*;
import java.util.concurrent.*;
class OrderPricingHttpTest extends ShippingAddressHttpTest {
 @org.springframework.test.context.bean.override.mockito.MockitoBean com.kitschcatch.backend.domain.order.toss.TossPaymentsClient pg;
 @Autowired PurchaseOrderRepository orders;
 @Autowired PaymentRepository payments;
 @BeforeEach void pricingSchema() throws Exception {
  if(System.getenv("ADDRESS_TEST_DB_URL")!=null)jdbc.execute(Files.readString(Path.of("src/main/resources/db/manual/095_order_pricing.sql")));
 }
 long seller; long address; long product;
 void item(long amount,long shipping) throws Exception {
  seller=user();address=create();
  product=posts.save(Post.builder().user(users.findById(seller).orElseThrow()).title("가격 상품").description("설명").price(amount).productCategory(ProductCategory.GOODS).productCondition(ProductCondition.NEW).productStatus(ProductStatus.ON_SALE).build()).getId();
  assertThat(call(seller,"PATCH","/api/posts/"+product+"/shipping","{\"shippingAmount\":"+shipping+"}").status()).isEqualTo(200);
 }
 Result quote() throws Exception {return call(owner,"POST","/api/orders/quotes","{\"postId\":"+product+",\"shippingAddressId\":"+address+"}");}
 String orderBody(Result quote,long amount) {return "{\"postId\":"+product+",\"shippingAddressId\":"+address+",\"amount\":"+amount+",\"paymentMethod\":\"CARD\",\"quoteId\":\""+quote.data().get("quoteId")+"\"}";}
 Result order(Result quote,long amount) throws Exception{return call(owner,"POST","/api/orders",orderBody(quote,amount));}
 @Test void totalsRetriesAndSellerPayoutUseProductOnly() throws Exception {
  item(30000,3000);var q=quote();assertThat(q.status()).isEqualTo(201);
  assertThat((Map)q.data().get("pricing")).containsEntry("totalAmount",33000).containsEntry("buyerFeeAmount",0);
  var made=order(q,33000);assertThat(made.status()).isEqualTo(201);
  assertThat(order(q,33000).data().get("orderId")).isEqualTo(made.data().get("orderId"));
  var o=orders.findByOrderNumber((String)made.data().get("orderId")).orElseThrow();
  assertThat(o.getPricing().getSellerFeeAmount()).isEqualTo(1500);assertThat(o.getPricing().getSellerPayoutAmount()).isEqualTo(31500);
  assertThat(jdbc.queryForObject("select amount from payments where order_id=?",Long.class,o.getId())).isEqualTo(33000);
 }
 @Test void changedPriceAddressShippingAndExpiryRequireNewQuote() throws Exception {
  item(10000,3000);var q=quote();
  call(seller,"PATCH","/api/posts/"+product+"/shipping","{\"shippingAmount\":3500}");
  assertThat(order(q,13000).status()).isEqualTo(409);
  q=quote();call(owner,"PATCH",root()+"/"+address,"{\"addressLine2\":\"변경\"}");assertThat(order(q,13500).status()).isEqualTo(409);
  q=quote();jdbc.update("update order_quotes set expires_at=CURRENT_TIMESTAMP-INTERVAL '1' HOUR where id=?",q.data().get("quoteId"));assertThat(order(q,13500).status()).isEqualTo(409);
 }
 @Test void ownershipTamperingAndLegacyBypassAreRejected() throws Exception {
  item(10000,3000);var q=quote();assertThat(order(q,10000).status()).isEqualTo(409);
  assertThat(call(user(),"POST","/api/orders",orderBody(q,13000)).status()).isEqualTo(409);
  assertThat(call(user(),"PATCH","/api/posts/"+product+"/shipping","{\"shippingAmount\":0}").status()).isEqualTo(403);
  assertThat(call(owner,"POST","/api/orders",orderBody(q,13000).replace(",\"quoteId\":\""+q.data().get("quoteId")+"\"","")).status()).isEqualTo(409);
  assertThat(order(q,13000).status()).isEqualTo(201);
  assertThat(call(seller,"PATCH","/api/posts/"+product+"/shipping","{\"shippingAmount\":0}").status()).isEqualTo(409);
 }
 @Test void concurrentQuoteConsumptionCreatesOnlyOneOrder() throws Exception {
  item(10000,3000);var q=quote();
  try(var pool=Executors.newFixedThreadPool(2)) {
   var gate=new CyclicBarrier(2);Callable<Result> c=()->{gate.await();return order(q,13000);};var a=pool.submit(c);var b=pool.submit(c);
   var one=a.get(20,TimeUnit.SECONDS);var two=b.get(20,TimeUnit.SECONDS);
   assertThat(one.status()).isEqualTo(201);assertThat(two.status()).isEqualTo(201);assertThat(one.data().get("orderId")).isEqualTo(two.data().get("orderId"));
  }
 }
 @Test void roundingAndOverflowAreExplicit() {
  assertThat(OrderPricing.calculate(19999,3000).getSellerFeeAmount()).isEqualTo(999);
  assertThat(OrderPricing.calculate(19999,3000).getSellerPayoutAmount()).isEqualTo(22000);
  assertThat(OrderPricing.calculate(1,0).total()).isEqualTo(1);
  assertThatThrownBy(()->OrderPricing.calculate(Long.MAX_VALUE,1)).isInstanceOf(RuntimeException.class);
  assertThatThrownBy(()->OrderPricing.calculate(1,-1)).isInstanceOf(IllegalArgumentException.class);
 }
 @org.junit.jupiter.params.ParameterizedTest
 @org.junit.jupiter.params.provider.ValueSource(ints={900,-1})
 void settlementUsesFrozenPayoutEvenWhenConfigChanges(int configuredFee) throws Exception {
  item(30000,3000);var q=quote();var made=order(q,33000);
  var o=orders.findByOrderNumber((String)made.data().get("orderId")).orElseThrow();
  var p=Payment.builder().order(o).paymentId("test").amount(33000L).paymentMethod(PaymentMethod.CARD).paymentStatus(PaymentStatus.READY).build();p.confirm("key");o.confirmPurchase();
  var access=mock(OrderAccessService.class);when(access.operatorOrder(anyLong(),anyString(),anySet(),eq(true))).thenReturn(o);
  var repo=mock(PaymentRepository.class);when(repo.findByOrderIdForUpdate(o.getId())).thenReturn(Optional.of(p));
  var recipients=mock(SettlementRecipientRepository.class);
  when(recipients.findById(seller)).thenReturn(Optional.of(new SettlementRecipient(seller,"seller-id",owner)));
  var service=new SettlementTransactionService(access,repo,new SettlementProperties(Set.of(owner),configuredFee<0?null:configuredFee),recipients);
  var prepared=service.prepare(owner,o.getOrderNumber(),true);
  assertThat(prepared.command().amount()).isEqualTo(31500);
  assertThat(prepared.command().fee()).isEqualTo(1500);
  assertThat(o.getPricing().getSellerFeeAmount()).isEqualTo(1500);
  assertThat(o.getPricing().getSellerPayoutAmount()).isEqualTo(31500);
 }

 @Test void approvalAndFullRefundUseTheSameTotal() throws Exception {
  item(30000,3000);var made=order(quote(),33000);String number=(String)made.data().get("orderId"),payment=(String)made.data().get("paymentId");
  when(pg.confirm(any())).thenAnswer(i->{var r=(com.kitschcatch.backend.domain.order.toss.TossPaymentConfirmRequest)i.getArgument(0);assertThat(r.amount()).isEqualTo(33000);return cardApproval(r.paymentKey(),r.orderId(),r.amount());});
  var approved=call(owner,"POST","/api/payments/"+payment+"/confirm","{\"paymentId\":\""+payment+"\",\"paymentKey\":\"pricing-key\"}");assertThat(approved.status()).isEqualTo(200);
  when(pg.cancel(any())).thenReturn(new com.kitschcatch.backend.domain.order.toss.TossPaymentResponse("pricing-key",(String)made.data().get("pgOrderId"),33000L,"CANCELED",0L,"2026-11-09T00:00:00+09:00","2026-11-09T00:00:01+09:00","cancel"));
  assertThat(call(owner,"POST","/api/orders/"+number+"/refunds","{\"reason\":\"취소\",\"amount\":30000}").status()).isEqualTo(400);
  var refunded=call(owner,"POST","/api/orders/"+number+"/refunds","{\"reason\":\"취소\",\"amount\":33000}");assertThat(refunded.status()).isEqualTo(200);assertThat(refunded.data()).containsEntry("amount",33000).containsEntry("status","COMPLETED");
  verify(pg,times(1)).confirm(any());verify(pg,times(1)).cancel(any());
 }

 @Test @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named="ADDRESS_TEST_DB_URL",matches=".+")
 void migrationCreatesRealConstraintsAndPreservesLegacyMoney() throws Exception {
  String schema="pricing_"+UUID.randomUUID().toString().replace("-","");
  try(var c=java.util.Objects.requireNonNull(jdbc.getDataSource()).getConnection();var st=c.createStatement()) {
   st.execute("create schema "+schema);
   try {
    st.execute("set search_path to "+schema);st.execute("create table users(id bigint primary key)");st.execute("create table posts(id bigint primary key)");st.execute("create table orders(id bigint primary key,amount bigint not null)");st.execute("insert into orders values(1,12000)");
    String sql=Files.readString(Path.of("src/main/resources/db/manual/095_order_pricing.sql"));st.execute(sql);st.execute(sql);
    try(var rows=st.executeQuery("select amount,pricing_policy from orders where id=1")){rows.next();assertThat(rows.getLong(1)).isEqualTo(12000);assertThat(rows.getString(2)).isNull();}
    assertThatThrownBy(()->st.execute("insert into posts(id,shipping_amount) values(1,-1)")).isInstanceOf(java.sql.SQLException.class);
    assertThatThrownBy(()->st.execute("insert into orders(id,amount,pricing_policy,quote_id,product_amount,shipping_amount,buyer_fee_amount,seller_fee_amount,seller_payout_amount) values(2,33000,'V2_SELLER_500_BUYER_0','quote',30000,3000,0,1650,31350)")).isInstanceOf(java.sql.SQLException.class);
    assertThatThrownBy(()->st.execute("insert into orders(id,amount,product_amount) values(3,12000,12000)")).isInstanceOf(java.sql.SQLException.class);
    st.execute("insert into orders(id,amount,pricing_policy,quote_id,product_amount,shipping_amount,buyer_fee_amount,seller_fee_amount,seller_payout_amount) values(2,33000,'V2_SELLER_500_BUYER_0','quote',30000,3000,0,1500,31500)");
   } finally {st.execute("set search_path to public");st.execute("drop schema "+schema+" cascade");}
  }
 }

 @Test void consumedQuoteStillReturnsCancelledOrderAfterSellerWithdrawal() throws Exception {
  item(30000,3000);var q=quote();var made=order(q,33000);String number=(String)made.data().get("orderId");
  assertThat(call(owner,"POST","/api/orders/"+number+"/cancel","{\"reason\":\"구매 취소\"}").status()).isEqualTo(200);
  assertThat(call(seller,"DELETE","/api/users/me",null).status()).isEqualTo(200);
  jdbc.update("update order_quotes set expires_at=CURRENT_TIMESTAMP-INTERVAL '1' HOUR where id=?",q.data().get("quoteId"));
  var replay=order(q,33000);assertThat(replay.status()).isEqualTo(201);
  assertThat(replay.data()).containsEntry("orderId",number).containsEntry("status","CANCELED");
  assertThat(jdbc.queryForObject("select count(*) from orders where quote_id=?",Integer.class,q.data().get("quoteId"))).isEqualTo(1);
  assertThat(posts.findById(product).orElseThrow().getActiveOrderNumber()).isNull();
 }
 @Test void unusedQuoteCannotCreateOrderForWithdrawnSeller() throws Exception {
  item(30000,3000);var q=quote();
  assertThat(call(seller,"DELETE","/api/users/me",null).status()).isEqualTo(200);
  assertThat(order(q,33000).status()).isEqualTo(401);
  assertThat(jdbc.queryForObject("select count(*) from orders where quote_id=?",Integer.class,q.data().get("quoteId"))).isZero();
 }
}
