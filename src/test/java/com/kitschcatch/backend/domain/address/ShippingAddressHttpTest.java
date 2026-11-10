// 실제 JWT와 HTTP 및 DB 경합으로 배송지와 주문 스냅샷 계약을 검증한다.
package com.kitschcatch.backend.domain.address;

import static org.assertj.core.api.Assertions.*;
import com.kitschcatch.backend.domain.user.entity.*;
import com.kitschcatch.backend.domain.user.repository.UserRepository;
import com.kitschcatch.backend.domain.post.entity.*;
import com.kitschcatch.backend.domain.post.repository.PostRepository;
import com.kitschcatch.backend.global.security.JwtTokenProvider;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
 "kakao.oauth.native-app-key=test-key","spring.jpa.hibernate.ddl-auto=create-drop","app.orders.expiration-enabled=false","springdoc.api-docs.enabled=true"})
class ShippingAddressHttpTest {
 @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
  String url=System.getenv("ADDRESS_TEST_DB_URL"); boolean pg=url!=null;
  r.add("spring.datasource.url",()->pg?url:"jdbc:h2:mem:addresses;MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
  r.add("spring.datasource.driver-class-name",()->pg?"org.postgresql.Driver":"org.h2.Driver");
  r.add("spring.datasource.username",()->pg?System.getenv().getOrDefault("ADDRESS_TEST_DB_USER",System.getProperty("user.name")):"sa"); r.add("spring.datasource.password",()->pg?System.getenv().getOrDefault("ADDRESS_TEST_DB_PASSWORD",""):"");
 }
 @LocalServerPort int port;
 @Autowired UserRepository users;
 @Autowired PostRepository posts;
 @Autowired JwtTokenProvider jwt;
 @Autowired JdbcTemplate jdbc;
 final HttpClient http=HttpClient.newHttpClient(); final JsonMapper json=JsonMapper.builder().build();
 long owner;
 @BeforeEach void setup() throws Exception {
  owner=user();
  if(System.getenv("ADDRESS_TEST_DB_URL")!=null) jdbc.execute(Files.readString(Path.of("src/main/resources/db/manual/093_shipping_addresses.sql")));
 }
 long user() { return users.save(User.builder().nickname("배송지테스트").email(UUID.randomUUID()+"@example.com").authProvider(AuthProvider.KAKAO).providerUserId(UUID.randomUUID().toString()).build()).getId(); }
 String root(){return "/api/users/me/addresses";}
 String body(){return "{\"label\":\"집\",\"recipientName\":\"홍길동\",\"recipientPhone\":\"010-1234-5678\",\"postalCode\":\"01234\",\"addressLine1\":\"서울 예시로 10\",\"addressLine2\":\"101호\"}";}
 record Result(int status,Map body) { Map data(){return (Map)body.get("data");} }
 Result call(long user,String method,String path,String body) throws Exception {
  var b=HttpRequest.newBuilder(URI.create("http://localhost:"+port+path)).header("Content-Type","application/json");
  if(user>0)b.header("Authorization","Bearer "+jwt.createAccessToken(user));
  var response=http.send(b.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
  return new Result(response.statusCode(),json.readValue(response.body(),Map.class));
 }
 long create() throws Exception {var r=call(owner,"POST",root(),body()); assertThat(r.status).isEqualTo(201); return ((Number)r.data().get("id")).longValue();}
 String quotedOrderBody(long post,long address,long amount) throws Exception {
  var q=call(owner,"POST","/api/orders/quotes","{\"postId\":"+post+",\"shippingAddressId\":"+address+"}");
  assertThat(q.status()).isEqualTo(201);
  return "{\"postId\":"+post+",\"amount\":"+amount+",\"paymentMethod\":\"CARD\",\"shippingAddressId\":"+address+",\"quoteId\":\""+q.data().get("quoteId")+"\"}";
 }
 @Test void crudDefaultsAndPatchPresence() throws Exception {
  assertThat((List)call(owner,"GET",root(),null).body.get("data")).isEmpty();
  long first=create(), second=create();
  assertThat(call(owner,"GET",root()+"/"+first,null).data()).containsEntry("isDefault",true).containsEntry("recipientPhone","01012345678");
  assertThat(call(owner,"PATCH",root()+"/"+second,"{\"isDefault\":true,\"addressLine2\":null}").status).isEqualTo(200);
  assertThat(call(owner,"GET",root()+"/"+second,null).data()).containsEntry("isDefault",true).containsEntry("recipientName","홍길동");
  assertThat(call(owner,"PATCH",root()+"/"+second,"{\"isDefault\":false}").status).isEqualTo(409);
  assertThat(call(owner,"DELETE",root()+"/"+second,null).status).isEqualTo(200);
  assertThat(call(owner,"GET",root()+"/"+first,null).data()).containsEntry("isDefault",true);
  assertThat(call(owner,"DELETE",root()+"/"+first,null).status).isEqualTo(200);
  assertThat(call(owner,"DELETE",root()+"/"+first,null).status).isEqualTo(404);
 }
 @Test void authOwnershipAndStrictInput() throws Exception {
  long id=create(), other=user();
  assertThat(call(0,"GET",root(),null).status).isEqualTo(401);
  for(String method:List.of("GET","PATCH","DELETE")) assertThat(call(other,method,root()+"/"+id,method.equals("PATCH")?"{\"label\":\"회사\"}":null).status).isEqualTo(404);
  for(String invalid:List.of("{}","{\"userId\":1}","{\"isDefault\":\"true\"}","{\"recipientName\":null}","{\"postalCode\":12345}","{\"addressLine1\":\" \"}"))
   assertThat(call(owner,"PATCH",root()+"/"+id,invalid).status).as(invalid).isEqualTo(400);
  assertThat(call(owner,"POST",root(),"{\"label\":\"집\"}").status).isEqualTo(400);
 }
 @Test void concurrentLimitAndDefaultChanges() throws Exception {
  List<Long> ids=new ArrayList<>(); for(int i=0;i<9;i++)ids.add(create());
  try(var pool=Executors.newFixedThreadPool(2)) {
   var gate=new CyclicBarrier(2);
   Callable<Integer> add=()->{gate.await();return call(owner,"POST",root(),body()).status;};
   var a=pool.submit(add);var b=pool.submit(add);
   assertThat(List.of(a.get(),b.get())).containsExactlyInAnyOrder(201,409);
   Callable<Integer> one=()->{gate.await();return call(owner,"PATCH",root()+"/"+ids.get(0),"{\"isDefault\":true}").status;};
   Callable<Integer> two=()->{gate.await();return call(owner,"PATCH",root()+"/"+ids.get(1),"{\"isDefault\":true}").status;};
   a=pool.submit(one);b=pool.submit(two); assertThat(List.of(a.get(),b.get())).containsOnly(200);
  }
  assertThat(jdbc.queryForObject("select count(*) from user_shipping_addresses where user_id=? and is_default=true",Integer.class,owner)).isEqualTo(1);
 }
 @Test void orderSnapshotSurvivesChangesAndHidesFromUnpaidSeller() throws Exception {
  long address=create(), seller=user();
  var post=posts.save(Post.builder().user(users.findById(seller).orElseThrow()).title("테스트 상품").description("설명").price(12000L).productCategory(ProductCategory.GOODS).productCondition(ProductCondition.NEW).productStatus(ProductStatus.ON_SALE).build());
  String orderBody=quotedOrderBody(post.getId(),address,15000);
  assertThat(call(seller,"POST","/api/orders",orderBody).status).isIn(400,403,409);
  var made=call(owner,"POST","/api/orders",orderBody); assertThat(made.status).isEqualTo(201);String number=(String)made.data().get("orderId");
  call(owner,"PATCH",root()+"/"+address,"{\"addressLine2\":\"202호\"}");call(owner,"DELETE",root()+"/"+address,null);
  var buyer=call(owner,"GET","/api/orders/"+number,null);assertThat(buyer.status).isEqualTo(200);
  assertThat((Map)buyer.data().get("shippingAddress")).containsEntry("addressLine2","101호");
  assertThat(call(seller,"GET","/api/orders/"+number,null).data().get("shippingAddress")).isNull();
  jdbc.update("update payments set approved_at=CURRENT_TIMESTAMP where order_id=(select id from orders where order_number=?)",number);
  assertThat(call(seller,"GET","/api/orders/"+number,null).data().get("shippingAddress")).isNotNull();
  assertThat(call(user(),"GET","/api/orders/"+number,null).status).isEqualTo(403);
 }
 @Test void withdrawalClearsAddressesAndRevokesAccess() throws Exception {
  create();assertThat(call(owner,"DELETE","/api/users/me",null).status).isEqualTo(200);
  assertThat(jdbc.queryForObject("select count(*) from user_shipping_addresses where user_id=?",Integer.class,owner)).isZero();
  assertThat(call(owner,"POST",root(),body()).status).isEqualTo(401);
 }
 @Test @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named="ADDRESS_TEST_DB_URL",matches=".+")
 void postgresMigrationIsRepeatableAndEnforcesDefaultUniqueness() throws Exception {
  long first=create(),second=create();
  jdbc.execute(Files.readString(Path.of("src/main/resources/db/manual/093_shipping_addresses.sql")));
  assertThat(call(owner,"GET",root()+"/"+first,null).status).isEqualTo(200);
  assertThatThrownBy(()->jdbc.update("update user_shipping_addresses set is_default=true where id=?",second)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
 }

 @Test void swaggerShowsAddressFields() throws Exception {
  var spec=call(0,"GET","/v3/api-docs",null);
  assertThat(spec.status).isEqualTo(200);
  assertThat(spec.body.toString()).contains("recipientPhone", "postalCode", "isDefault", "/api/users/me/addresses/{id}");
 }
 @Test void addressDeletionRacesOrderCreationSafely() throws Exception {
  long address=create(), seller=user();
  var post=posts.save(Post.builder().user(users.findById(seller).orElseThrow()).title("경합 상품").description("설명").price(12000L).productCategory(ProductCategory.GOODS).productCondition(ProductCondition.NEW).productStatus(ProductStatus.ON_SALE).build());
  String payload=quotedOrderBody(post.getId(),address,15000);
  try(var pool=Executors.newFixedThreadPool(2)) {
   var gate=new CyclicBarrier(2);
   var order=pool.submit(()->{gate.await();return call(owner,"POST","/api/orders",payload);});
   var deleted=pool.submit(()->{gate.await();return call(owner,"DELETE",root()+"/"+address,null);});
   var result=order.get();assertThat(deleted.get().status).isEqualTo(200);
   assertThat(result.status).isIn(201,404,409);
   if(result.status==201) assertThat(call(owner,"GET","/api/orders/"+result.data().get("orderId"),null).data().get("shippingAddress")).isNotNull();
  }
 }
 @Test void withdrawalRacesRegistrationWithoutLeavingAddresses() throws Exception {
  try(var pool=Executors.newFixedThreadPool(2)) {
   var gate=new CyclicBarrier(2);
   var add=pool.submit(()->{gate.await();return call(owner,"POST",root(),body());});
   var leave=pool.submit(()->{gate.await();return call(owner,"DELETE","/api/users/me",null);});
   assertThat(add.get().status).isIn(201,401);assertThat(leave.get().status).isEqualTo(200);
  }
  assertThat(jdbc.queryForObject("select count(*) from user_shipping_addresses where user_id=?",Integer.class,owner)).isZero();
 }

 @Test @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named="ADDRESS_TEST_DB_URL",matches=".+")
 void migrationCreatesConstraintsAndPreservesLegacyOrders() throws Exception {
  String schema="address_migration_"+UUID.randomUUID().toString().replace("-","");
  String migration=Files.readString(Path.of("src/main/resources/db/manual/093_shipping_addresses.sql"));
  try(var connection=java.util.Objects.requireNonNull(jdbc.getDataSource()).getConnection();var statement=connection.createStatement()) {
   statement.execute("CREATE SCHEMA "+schema);
   try {
    statement.execute("SET search_path TO "+schema);
    statement.execute("CREATE TABLE users(id BIGINT PRIMARY KEY)");
    statement.execute("CREATE TABLE orders(id BIGINT PRIMARY KEY, amount BIGINT NOT NULL)");
    statement.execute("INSERT INTO users VALUES(1)");
    statement.execute("INSERT INTO orders VALUES(1,12000)");
    statement.execute(migration);statement.execute(migration);
    try(var rows=statement.executeQuery("SELECT amount,shipping_recipient_name,shipping_address_line1 FROM orders WHERE id=1")) {
     assertThat(rows.next()).isTrue();assertThat(rows.getLong(1)).isEqualTo(12000);
     assertThat(rows.getString(2)).isNull();assertThat(rows.getString(3)).isNull();
    }
    String insert="INSERT INTO user_shipping_addresses(user_id,recipient_name,recipient_phone,postal_code,address_line1,is_default,created_at,updated_at) VALUES";
    statement.execute(insert+"(1,'수령인','01012345678','01234','서울',true,now(),now())");
    for(String invalid:List.of(
      "(1,'수령인','01012345678','01234','서울',true,now(),now())",
      "(999,'수령인','01012345678','01234','서울',false,now(),now())",
      "(1,'수령인','invalid','01234','서울',false,now(),now())",
      "(1,'수령인','01012345678','1234','서울',false,now(),now())",
      "(1,' ','01012345678','01234','서울',false,now(),now())")) {
     assertThatThrownBy(()->statement.execute(insert+invalid)).isInstanceOf(java.sql.SQLException.class);
    }
    statement.execute("DELETE FROM users WHERE id=1");
    try(var rows=statement.executeQuery("SELECT count(*) FROM user_shipping_addresses")) { rows.next();assertThat(rows.getInt(1)).isZero(); }
   } finally { statement.execute("SET search_path TO public");statement.execute("DROP SCHEMA "+schema+" CASCADE"); }
  }
 }
}
