// 실제 PostgreSQL과 HTTP에서 신고 권한과 동시 접수 및 보관 기한을 검증한다.
package com.kitschcatch.backend.domain.report;
import static org.assertj.core.api.Assertions.*;
import com.kitschcatch.backend.domain.user.entity.*;
import com.kitschcatch.backend.domain.user.repository.UserRepository;
import com.kitschcatch.backend.domain.post.entity.*;
import com.kitschcatch.backend.domain.post.repository.PostRepository;
import com.kitschcatch.backend.domain.chat.entity.ChatRoom;
import com.kitschcatch.backend.domain.chat.repository.ChatRoomRepository;
import com.kitschcatch.backend.domain.block.service.UserBlockService;
import com.kitschcatch.backend.domain.store.entity.*;
import com.kitschcatch.backend.domain.store.repository.*;
import com.kitschcatch.backend.global.security.JwtTokenProvider;
import java.sql.DriverManager;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.web.client.RestClient;
import org.springframework.http.MediaType;

@EnabledIfEnvironmentVariable(named="ISSUE91_TEST_DB_URL",matches="jdbc:postgresql:.*")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "spring.datasource.driver-class-name=org.postgresql.Driver","spring.jpa.hibernate.ddl-auto=create",
    "kakao.oauth.native-app-key=test-key","app.orders.expiration-enabled=false",
    "app.payments.recovery.enabled=false","app.notifications.push.enabled=false",
    "app.reports.cleanup-enabled=false"})
@DirtiesContext
class ReportPostgresTest {
    static final String SCHEMA="issue91_"+UUID.randomUUID().toString().replace("-","");
    static final String URL=System.getenv("ISSUE91_TEST_DB_URL");
    static final String USER=System.getenv().getOrDefault("ISSUE91_TEST_DB_USERNAME","postgres");
    static final String PASSWORD=System.getenv().getOrDefault("ISSUE91_TEST_DB_PASSWORD","");
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry r) throws Exception {
        try(var c=DriverManager.getConnection(URL,USER,PASSWORD);var s=c.createStatement()){s.execute("create schema "+SCHEMA);}
        r.add("spring.datasource.url",()->URL);r.add("spring.datasource.username",()->USER);
        r.add("spring.datasource.password",()->PASSWORD);r.add("spring.datasource.hikari.schema",()->SCHEMA);
        r.add("spring.jpa.properties.hibernate.default_schema",()->SCHEMA);
    }
    @BeforeAll static void migrate(@Autowired JdbcTemplate ignored) throws Exception {
        try(var c=DriverManager.getConnection(URL,USER,PASSWORD);var s=c.createStatement();
            var f=ReportPostgresTest.class.getResourceAsStream("/db/manual/091_reports.sql")) {
            c.setSchema(SCHEMA);String sql=new String(f.readAllBytes(),StandardCharsets.UTF_8);
            s.execute(sql);s.execute(sql);
        }
    }
    @AfterAll static void drop() throws Exception {
        try(var c=DriverManager.getConnection(URL,USER,PASSWORD);var s=c.createStatement()){s.execute("drop schema "+SCHEMA+" cascade");}
    }
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired UserRepository users;
    @Autowired PostRepository posts;
    @Autowired ChatRoomRepository rooms;
    @Autowired StoreRepository stores;
    @Autowired StoreReviewRepository reviews;
    @Autowired JwtTokenProvider jwt;
    @Autowired ReportService service;
    @Autowired ReportRepository reports;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
    @Autowired UserBlockService blocks;
    User a,b,c;Post post;long room,review;
    @BeforeEach void setup() {
        jdbc.execute("truncate reports,store_review_images,store_reviews,store_business_hours,store_images,store_amenities,stores,post_images,chat_messages,chat_rooms,posts,user_blocks,user_follows,users cascade");
        a=user();b=user();c=user();
        post=posts.saveAndFlush(Post.builder().user(b).title("원래 제목").description("원래 설명").price(100L)
            .productCategory(ProductCategory.GOODS).productCondition(ProductCondition.NEW).productStatus(ProductStatus.ON_SALE).build());
        room=rooms.saveAndFlush(ChatRoom.create(post,a,b)).getId();
        var store=stores.saveAndFlush(Store.builder().name("테스트 매장").region("서울").address("테스트 주소").latitude(37).longitude(127).build());
        review=reviews.saveAndFlush(new StoreReview(store,b,4,"원래 리뷰",Instant.now())).getId();
    }
    User user(){return users.saveAndFlush(User.builder().nickname("사용자").authProvider(AuthProvider.KAKAO).providerUserId(UUID.randomUUID().toString()).build());}
    Map<String,Object> body(String type,long id,String reason){return Map.of("targetType",type,"targetId",id,"reasonCode",reason);}
    @Test void reasonsAndStrictInputs() {
        assertThat(call("GET","/api/reports/reasons?targetType=POST",null,null).status).isEqualTo(401);
        assertThat(call("GET","/api/reports/reasons?targetType=POST",a,null).status).isEqualTo(200);
        for(Object input:List.of(Map.of("targetType","POST","targetId",1.5,"reasonCode","SPAM"),
            Map.of("targetType","POST","targetId",post.getId(),"reasonCode","SPAM","reporterId",b.getId()),
            body("POST",post.getId(),"IMPERSONATION"),body("POST",post.getId(),"OTHER"),
            Map.of("targetType","POST","targetId",post.getId(),"reasonCode","SPAM","messageIds",List.of()))) {
            assertThat(call("POST","/api/reports",a,input).status).isEqualTo(400);
        }
        assertThat(call("POST","/api/reports",a,Map.of("targetType","USER","targetId",b.getId(),"reasonCode","SPAM","description","x".repeat(1001))).status).isEqualTo(400);
    }
    @Test void allTargetsOwnershipAndPrivateDetail() {
        for(var input:List.of(body("USER",b.getId(),"SPAM"),body("POST",post.getId(),"SPAM"),body("CHAT_ROOM",room,"SPAM"),body("STORE_REVIEW",review,"OFF_TOPIC"))) {
            var response=call("POST","/api/reports",a,input);assertThat(response.status).as("%s",response.body).isEqualTo(201);
            long id=((Number)response.data().get("reportId")).longValue();
            assertThat(call("GET","/api/reports/"+id,a,null).data()).doesNotContainKeys("targetSnapshot","targetUserId","reporterId");
            assertThat(call("GET","/api/reports/"+id,c,null).status).isEqualTo(404);
        }
        assertThat(call("POST","/api/reports",c,body("CHAT_ROOM",room,"SPAM")).status).isEqualTo(404);
        for(var input:List.of(body("USER",b.getId(),"SPAM"),body("POST",post.getId(),"SPAM"),body("STORE_REVIEW",review,"SPAM")))
            assertThat(call("POST","/api/reports",b,input).status).isEqualTo(400);
        assertThat(call("GET","/api/users/me/reports?size=101",a,null).status).isEqualTo(400);
        assertThat(call("GET","/api/users/me/reports",a,null).data()).containsEntry("totalElements",4);
        assertThat(call("GET","/api/users/me/reports",c,null).data()).containsEntry("totalElements",0);
    }
    @Test void snapshotAndRetrySurviveSourceDeletion() {
        var request=body("POST",post.getId(),"SPAM");
        var first=call("POST","/api/reports",a,request);assertThat(first.status).isEqualTo(201);
        jdbc.update("update posts set title='수정',deleted_at=current_timestamp where id=?",post.getId());
        assertThat(call("POST","/api/reports",a,request).data()).isEqualTo(first.data());
        assertThat(call("POST","/api/reports",a,body("POST",post.getId(),"OTHER")).status).isEqualTo(400);
        assertThat(call("POST","/api/reports",a,body("POST",post.getId(),"INAPPROPRIATE_CONTENT")).status).isEqualTo(409);
        assertThat(call("POST","/api/reports",c,request).status).isEqualTo(404);
        assertThat(jdbc.queryForObject("select target_snapshot->>'title' from reports",String.class)).isEqualTo("원래 제목");
    }
    @Test void blockedChatAndSelectedEvidenceDoNotChangeUnread() {
        long message=jdbc.queryForObject("insert into chat_messages(chat_room_id,sender_id,message_type,content,created_at,is_read) values(?,?,'TEXT','문제 내용',current_timestamp,false) returning id",Long.class,room,b.getId());
        blocks.change(a.getId(),b.getId(),true);
        var request=Map.of("targetType","CHAT_ROOM","targetId",room,"reasonCode","HARASSMENT","messageIds",List.of(message));
        assertThat(call("POST","/api/reports",a,request).status).isEqualTo(201);
        assertThat(jdbc.queryForObject("select target_snapshot->'messages'->0->>'content' from reports",String.class)).isEqualTo("문제 내용");
        assertThat(jdbc.queryForObject("select is_read from chat_messages where id=?",Boolean.class,message)).isFalse();
        assertThat(jdbc.queryForObject("select count(*) from user_blocks",Long.class)).isEqualTo(1);
        jdbc.update("delete from reports");
        assertThat(call("POST","/api/reports",a,Map.of("targetType","CHAT_ROOM","targetId",room,"reasonCode","SPAM","messageIds",List.of(message,message))).status).isEqualTo(400);
        assertThat(call("POST","/api/reports",a,Map.of("targetType","CHAT_ROOM","targetId",room,"reasonCode","SPAM","messageIds",List.of(Long.MAX_VALUE))).status).isEqualTo(400);
        jdbc.update("update users set withdrawn_at=current_timestamp where id=?",b.getId());
        assertThat(call("POST","/api/reports",a,request).status).isEqualTo(201);
    }
    @Test void expiryCleanupAndResubmission() {
        var input=body("USER",b.getId(),"SPAM");assertThat(call("POST","/api/reports",a,input).status).isEqualTo(201);
        jdbc.update("update reports set created_at=current_timestamp-interval '91 days',expires_at=current_timestamp-interval '1 day'");
        assertThat(call("GET","/api/users/me/reports",a,null).data()).containsEntry("totalElements",0);
        assertThat(call("POST","/api/reports",a,input).status).isEqualTo(201);
        assertThat(reports.cleanup()).isZero();
        jdbc.update("update reports set created_at=current_timestamp-interval '91 days',expires_at=current_timestamp-interval '1 day'");
        assertThat(reports.cleanup()).isEqualTo(1);
    }
    @Test void concurrencyCreatesExactlyOneAndMinuteLimitIsPersistent() throws Exception {
        var request=new ReportRequest(ReportType.POST,post.getId(),"SPAM","",List.of());
        try(var pool=Executors.newFixedThreadPool(6)) {
            var start=new CountDownLatch(1);List<Future<Boolean>> results=new ArrayList<>();
            for(int i=0;i<6;i++)results.add(pool.submit(()->{start.await();return service.create(a.getId(),request).created();}));
            start.countDown();int created=0;for(var f:results)if(f.get(20,TimeUnit.SECONDS))created++;
            assertThat(created).isEqualTo(1);
        }
        for(int i=0;i<4;i++) assertThat(call("POST","/api/reports",a,body("USER",user().getId(),"SPAM")).status).isEqualTo(201);
        assertThat(call("POST","/api/reports",a,body("USER",c.getId(),"SPAM")).status).isEqualTo(429);
        assertThat(call("POST","/api/reports",a,body("POST",post.getId(),"SPAM")).status).isEqualTo(200);
    }
    @Test void dailyLimitAndSourceVisibility() {
        for(int i=0;i<20;i++) {
            assertThat(call("POST","/api/reports",a,body("USER",user().getId(),"SPAM")).status).isEqualTo(201);
            jdbc.update("update reports set created_at=current_timestamp-interval '2 minutes'");
        }
        assertThat(call("POST","/api/reports",a,body("USER",c.getId(),"SPAM")).status).isEqualTo(429);
        jdbc.update("update store_reviews set hidden_at=current_timestamp where id=?",review);
        assertThat(call("POST","/api/reports",c,body("STORE_REVIEW",review,"SPAM")).status).isEqualTo(404);
        jdbc.update("update users set withdrawn_at=current_timestamp where id=?",b.getId());
        assertThat(call("POST","/api/reports",c,body("USER",b.getId(),"SPAM")).status).isEqualTo(404);
    }
    @Test void deletionCommittedWhileReportWaitsIsRechecked() throws Exception {
        try(var pool=Executors.newSingleThreadExecutor()) {
            var pending=new java.util.concurrent.atomic.AtomicReference<Future<Response>>();
            new org.springframework.transaction.support.TransactionTemplate(transactions).executeWithoutResult(tx -> {
                jdbc.queryForObject("select id from users where id=? for update",Long.class,b.getId());
                pending.set(pool.submit(()->call("POST","/api/reports",a,body("POST",post.getId(),"SPAM"))));
                org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(10)).until(() ->
                    jdbc.queryForObject("select count(*) from pg_stat_activity where wait_event_type='Lock' and query like '%users%'",Long.class)>0);
                jdbc.update("update posts set deleted_at=current_timestamp where id=?",post.getId());
            });
            assertThat(pending.get().get(15,TimeUnit.SECONDS).status).isEqualTo(404);
            assertThat(jdbc.queryForObject("select count(*) from reports",Long.class)).isZero();
        }
    }
    @Test void privacyUnicodeAndForeignRoomMessages() {
        assertThat(call("POST","/api/reports",a,Map.of("targetType","USER","targetId",b.getId(),"reasonCode","SPAM","description","😀".repeat(1000))).status).isEqualTo(201);
        assertThat(jdbc.queryForObject("select target_snapshot::text from reports",String.class)).doesNotContain("email","provider_user_id");
        long other=rooms.saveAndFlush(ChatRoom.create(post,c,b)).getId();
        long message=jdbc.queryForObject("insert into chat_messages(chat_room_id,sender_id,message_type,content,created_at,is_read) values(?,?,'TEXT','다른 대화',current_timestamp,false) returning id",Long.class,other,b.getId());
        assertThat(call("POST","/api/reports",a,Map.of("targetType","CHAT_ROOM","targetId",room,"reasonCode","SPAM","messageIds",List.of(message))).status).isEqualTo(400);
        assertThat(jdbc.queryForObject("select count(*) from reports",Long.class)).isEqualTo(1);
    }
    @SuppressWarnings("unchecked")
    Response call(String method,String path,User actor,Object body) {
        var request=RestClient.create("http://127.0.0.1:"+port).method(org.springframework.http.HttpMethod.valueOf(method)).uri(path);
        if(actor!=null)request.header("Authorization","Bearer "+jwt.createAccessToken(actor.getId()));
        if(body!=null)request.contentType(MediaType.APPLICATION_JSON).body(body);
        return request.exchange((req,res)->new Response(res.getStatusCode().value(),res.bodyTo(Map.class)));
    }
    @SuppressWarnings("unchecked") record Response(int status,Map<String,Object> body) { Map<String,Object> data(){return (Map<String,Object>)body.get("data");} }
}
