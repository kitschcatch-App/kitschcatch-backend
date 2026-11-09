// 실제 PostgreSQL과 HTTP에서 신고 권한과 동시 접수 및 보관 기한을 검증한다.
package com.kitschcatch.backend.domain.report;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
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
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.client.RestClient;
import org.springframework.http.MediaType;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;

@EnabledIfEnvironmentVariable(named="ISSUE91_TEST_DB_URL",matches="jdbc:postgresql:.*")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "spring.datasource.driver-class-name=org.postgresql.Driver","spring.jpa.hibernate.ddl-auto=create",
    "kakao.oauth.native-app-key=test-key","app.orders.expiration-enabled=false",
    "app.payments.recovery.enabled=false","app.notifications.push.enabled=false",
    "app.reports.cleanup-enabled=false","app.s3.bucket=report-test",
    "app.s3.chat-image-prefix=chats","app.s3.public-base-url=https://images.example.test/media"})
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
    @BeforeAll static void migrate(@Autowired JdbcTemplate jdbc, @Autowired UserRepository users) throws Exception {
        try(var c=DriverManager.getConnection(URL,USER,PASSWORD);var s=c.createStatement();
            var f=ReportPostgresTest.class.getResourceAsStream("/db/manual/091_reports.sql")) {
            c.setSchema(SCHEMA);String sql=new String(f.readAllBytes(),StandardCharsets.UTF_8);
            s.execute(sql);s.execute(sql);
        }
        long reporter = users.saveAndFlush(User.builder().nickname("신고자").authProvider(AuthProvider.KAKAO)
            .providerUserId("legacy-reporter").build()).getId();
        long subject = users.saveAndFlush(User.builder().nickname("대상").authProvider(AuthProvider.KAKAO)
            .providerUserId("legacy-subject").build()).getId();
        jdbc.update("""
            insert into reports(reporter_id,target_type,target_id,target_user_id,reason_code,description,
                request_fingerprint,message_ids,target_snapshot,created_at,expires_at)
            values (?,'USER',?,?,'SPAM','기존 신고','old','[]','{}',current_timestamp,current_timestamp+interval '1 day')
            """, reporter, subject, subject);
        try(var c=DriverManager.getConnection(URL,USER,PASSWORD);var s=c.createStatement();
            var f=ReportPostgresTest.class.getResourceAsStream("/db/manual/107_report_reviews.sql")) {
            c.setSchema(SCHEMA);String sql=new String(f.readAllBytes(),StandardCharsets.UTF_8);
            s.execute(sql);s.execute(sql);
        }
        assertThat(jdbc.queryForObject("""
            select status='RECEIVED' and version=0 and created_at=updated_at and description='기존 신고'
            from reports where reporter_id=?
            """, Boolean.class, reporter)).isTrue();
        assertThat(jdbc.queryForObject("select count(*) from report_review_history", Long.class)).isZero();
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
    @MockitoBean S3Client s3;
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

    @Test void adminPermissionsApplyToEveryRouteAndExistingTokensAfterRevocation() {
        long id = reportId(body("USER", b.getId(), "SPAM"));
        String path = admin(id);
        String token = jwt.createAccessToken(c.getId());
        for (String endpoint : List.of("/api/admin/reports", path, path + "/history", path + "/evidence/messages",
            path + "/evidence/messages/1/image")) {
            assertThat(call("GET", endpoint, null, null).status).isEqualTo(401);
            assertThat(callToken("GET", endpoint, token, null).status).isEqualTo(403);
        }
        assertThat(callToken("PATCH", path + "/status", token, change("IN_REVIEW", 0)).status).isEqualTo(403);
        grant(a);
        grant(b);
        for (var participant : List.of(a, b)) {
            assertThat(call("GET", path, participant, null).status).isEqualTo(403);
            assertThat(call("GET", "/api/admin/reports", participant, null).data()).containsEntry("totalElements", 0);
            assertThat(call("PATCH", path + "/status", participant, change("IN_REVIEW", 0)).status).isEqualTo(403);
        }
        grant(c);
        assertThat(callToken("GET", path, token, null).status).isEqualTo(200);
        jdbc.update("update report_reviewers set revoked_at=clock_timestamp() where user_id=?", c.getId());
        assertThat(callToken("GET", path, token, null).status).isEqualTo(403);
        jdbc.update("update users set withdrawn_at=clock_timestamp() where id=?", c.getId());
        assertThat(callToken("GET", path, token, null).status).isEqualTo(401);
        assertThat(jdbc.queryForObject("select count(*) from report_review_access_log", Long.class)).isEqualTo(1);
        verifyNoInteractions(s3);
    }

    @Test void adminQueueFiltersAndReturnsOldestFirstWithPrivateDetail() {
        long older = reportId(body("POST", post.getId(), "SPAM"));
        long newer = reportId(body("USER", b.getId(), "SPAM"));
        jdbc.update("update reports set created_at='2025-01-01T00:00:00Z' where id in (?,?)", older, newer);
        grant(c);
        var page = call("GET", "/api/admin/reports?status=RECEIVED&status=IN_REVIEW&size=1", c, null);
        assertThat(page.status).isEqualTo(200);
        assertThat(page.data()).containsEntry("totalElements", 2).containsEntry("totalPages", 2);
        assertThat(entries(page.data(), "content").getFirst()).containsEntry("reportId", (int)older)
            .doesNotContainKeys("description", "messageIds", "targetSnapshot");
        assertThat(entries(call("GET", "/api/admin/reports?targetType=USER&reasonCode=SPAM&reporterId=" + a.getId()
            + "&targetUserId=" + b.getId() + "&from=2025-01-01T00:00:00Z&to=2025-01-02T00:00:00Z", c, null).data(),
            "content").getFirst()).containsEntry("reportId", (int)newer);
        jdbc.update("update posts set title='다른 제목',deleted_at=clock_timestamp() where id=?", post.getId());
        assertThat(call("GET", admin(older), c, null).data().get("targetSnapshot")).asString()
            .contains("원래 제목").doesNotContain("다른 제목", "object_key", "email", "provider_user_id");
        assertThat(call("GET", "/api/admin/reports?size=101", c, null).status).isEqualTo(400);
    }

    @Test void reviewChangesAreVersionedReplayableAndPrivateFromTheReporter() {
        String path = admin(reportId(body("USER", b.getId(), "SPAM")));
        grant(c);
        var second = user();
        grant(second);
        var start = change("IN_REVIEW", 0);
        assertThat(call("PATCH", path + "/status", c, change("RESOLVED", 0)).status).isEqualTo(409);
        assertThat(call("PATCH", path + "/status", c, Map.of("status", "IN_REVIEW", "expectedVersion", 0.5,
            "requestId", UUID.randomUUID(), "actorId", a.getId())).status).isEqualTo(400);
        var started = call("PATCH", path + "/status", c, start);
        assertThat(started.status).isEqualTo(200);
        var noReason = new HashMap<>(change("RESOLVED", 1));
        noReason.put("reason", "   ");
        assertThat(call("PATCH", path + "/status", c, noReason).status).isEqualTo(400);
        assertThat(call("PATCH", path + "/status", second, change("RESOLVED", 1)).status).isEqualTo(200);
        var replay = call("PATCH", path + "/status", c, start);
        assertThat(replay.data()).containsEntry("historyId", started.data().get("historyId")).containsEntry("replayed", true);
        assertThat(replay.data().get("report")).asString().contains("RESOLVED");
        var mismatched = new HashMap<>(start);
        mismatched.put("reason", "변조");
        assertThat(call("PATCH", path + "/status", c, mismatched).status).isEqualTo(409);
        assertThat(call("PATCH", path + "/status", c, change("DISMISSED", 2)).status).isEqualTo(409);
        var history = entries(call("GET", path + "/history", c, null).data(), "changes");
        assertThat(history).hasSize(2);
        assertThat(history.getFirst()).containsEntry("fromStatus", "RECEIVED").containsEntry("actorId", c.getId().intValue());
        assertThat(history.getLast()).containsEntry("toStatus", "RESOLVED").containsEntry("actorId", second.getId().intValue());
        assertThat(call("GET", path, c, null).data()).containsEntry("reviewStartedBy", c.getId().intValue())
            .containsEntry("closedBy", second.getId().intValue());
        var own = call("GET", path.replace("/admin", ""), a, null).data();
        assertThat(own).containsEntry("status", "RESOLVED")
            .doesNotContainKeys("closedBy", "reviewStartedBy", "history", "targetSnapshot");
    }

    @Test void concurrentOperatorsCanOnlyCloseTheReportOnce() throws Exception {
        String path = admin(reportId(body("USER", b.getId(), "SPAM")));
        grant(c);
        var second = user();
        grant(second);
        assertThat(call("PATCH", path + "/status", c, change("IN_REVIEW", 0)).status).isEqualTo(200);
        var gate = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var resolved = pool.submit(() -> {gate.await();return call("PATCH", path + "/status", c, change("RESOLVED", 1));});
            var dismissed = pool.submit(() -> {gate.await();return call("PATCH", path + "/status", second, change("DISMISSED", 1));});
            gate.countDown();
            assertThat(List.of(resolved.get(10, TimeUnit.SECONDS).status, dismissed.get(10, TimeUnit.SECONDS).status))
                .containsExactlyInAnyOrder(200, 409);
        }
        assertThat(jdbc.queryForObject("select count(*) from report_review_history", Long.class)).isEqualTo(2);
    }

    @Test void reportExpiringWhileAReviewWaitsCannotBeUpdated() throws Exception {
        long id = reportId(body("USER", b.getId(), "SPAM"));
        grant(c);
        var pending = new java.util.concurrent.atomic.AtomicReference<Future<Response>>();
        try (var executor = Executors.newSingleThreadExecutor()) {
            new org.springframework.transaction.support.TransactionTemplate(transactions).executeWithoutResult(tx -> {
                jdbc.queryForObject("select id from reports where id=? for update", Long.class, id);
                pending.set(executor.submit(() -> call("PATCH", admin(id) + "/status", c, change("IN_REVIEW", 0))));
                org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(10)).until(() ->
                    jdbc.queryForObject("""
                        select count(*) from pg_stat_activity where wait_event_type='Lock'
                        and query like '%from reports where id = %for update%'
                        """, Long.class) > 0);
                jdbc.update("""
                    update reports set created_at=clock_timestamp()-interval '91 days',
                        expires_at=clock_timestamp()-interval '1 day' where id=?
                    """, id);
            });
            assertThat(pending.get().get(10, TimeUnit.SECONDS).status).isEqualTo(404);
        }
        assertThat(reports.cleanup()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from report_review_history", Long.class)).isZero();
    }

    @Test void reviewerScriptAuditsOnlyActualGrantAndRevocation() throws Exception {
        String script = java.nio.file.Files.readString(java.nio.file.Path.of("scripts/report-reviewer.sql"))
            .replace("\\set ON_ERROR_STOP on", "")
            .replace(":'reviewer_id'", "'" + c.getId() + "'")
            .replace(":'reason'", "'검증 티켓'");
        String enable = script.replace(":'enabled'", "'true'");
        String disable = script.replace(":'enabled'", "'false'");
        jdbc.execute(enable);
        jdbc.execute(enable);
        assertThat(call("GET", "/api/admin/reports", c, null).status).isEqualTo(200);
        jdbc.execute(disable);
        jdbc.execute(disable);
        assertThat(call("GET", "/api/admin/reports", c, null).status).isEqualTo(403);
        assertThat(jdbc.queryForList("select enabled from report_reviewer_events order by id", Boolean.class))
            .containsExactly(true, false);
    }

    @Test void selectedEvidenceUsesSnapshotsAndAuthorizesOnlyExactImageKeys() throws Exception {
        long text = message("TEXT", "선택 메시지", null);
        long skipped = message("TEXT", "선택하지 않은 메시지", null);
        String key = "chats/" + room + "/" + b.getId() + "/7cd8d012-ec43-4768-8f87-bbb1d2e3c812.png";
        long image = message("IMAGE", null, "https://images.example.test/media/" + key + "?secret=value");
        long otherImage = message("IMAGE", null, "https://images.example.test/media/chats/999/" + b.getId()
            + "/7cd8d012-ec43-4768-8f87-bbb1d2e3c812.png");
        String path = admin(reportId(Map.of("targetType", "CHAT_ROOM", "targetId", room, "reasonCode", "HARASSMENT",
            "messageIds", List.of(text, image, otherImage))));
        grant(c);
        jdbc.update("update chat_messages set content='수정된 메시지' where id=?", text);
        jdbc.update("delete from chat_messages where id=?", image);
        var detail = call("GET", path, c, null);
        assertThat(detail.body.toString()).doesNotContain("선택 메시지", "imageReferencePath", key);
        String evidence = path + "/evidence/messages";
        var messages = call("GET", evidence, c, null);
        assertThat(messages.status).isEqualTo(200);
        assertThat(messages.body.toString()).contains("선택 메시지", "hasImageReference=true")
            .doesNotContain("수정된 메시지", "선택하지 않은 메시지", "secret=value", key);
        assertThat(call("GET", evidence + "/" + skipped + "/image", c, null).status).isEqualTo(404);
        assertThat(call("GET", evidence + "/" + otherImage + "/image", c, null).status).isEqualTo(404);
        verifyNoInteractions(s3);

        byte[] png = {1, 2, 3};
        var expected = GetObjectResponse.builder().contentType("image/png").contentLength(3L).build();
        when(s3.getObject(any(GetObjectRequest.class))).thenReturn(new ResponseInputStream<>(expected, new ByteArrayInputStream(png)))
            .thenThrow(S3Exception.builder().statusCode(404).build());
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + evidence + "/" + image + "/image"))
            .header("Authorization", "Bearer " + jwt.createAccessToken(c.getId())).GET().build();
        var response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofByteArray());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).containsExactly(png);
        assertThat(response.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");
        verify(s3).getObject(argThat((GetObjectRequest value) -> value.bucket().equals("report-test") && value.key().equals(key)));
        assertThat(call("GET", evidence + "/" + image + "/image", c, null).status).isEqualTo(404);
        assertThat(jdbc.queryForObject("select count(*) from report_review_access_log where access_type='IMAGE' and message_id=?",
            Long.class, image)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select is_read from chat_messages where id=?", Boolean.class, text)).isFalse();
    }

    @Test void expiredReportDeletesHistoryAndAccessLogsThroughBothCleanupPaths() {
        grant(c);
        var input = body("USER", b.getId(), "SPAM");
        for (boolean resubmit : List.of(false, true)) {
            String path = admin(reportId(input));
            assertThat(call("PATCH", path + "/status", c, change("IN_REVIEW", 0)).status).isEqualTo(200);
            assertThat(call("GET", path, c, null).status).isEqualTo(200);
            jdbc.update("update reports set created_at=clock_timestamp()-interval '91 days',expires_at=clock_timestamp()-interval '1 day'");
            assertThat(call("GET", path + "/history", c, null).status).isEqualTo(404);
            assertThat(call("PATCH", path + "/status", c, change("RESOLVED", 1)).status).isEqualTo(404);
            if (resubmit) {
                assertThat(call("POST", "/api/reports", a, input).status).isEqualTo(201);
            } else {
                assertThat(reports.cleanup()).isEqualTo(1);
            }
            assertThat(jdbc.queryForObject("select count(*) from report_review_history", Long.class)).isZero();
            assertThat(jdbc.queryForObject("select count(*) from report_review_access_log", Long.class)).isZero();
        }
    }

    private void grant(User operator) {
        jdbc.update("insert into report_reviewers(user_id,granted_at) values (?,clock_timestamp())", operator.getId());
    }
    private long message(String type, String content, String imageUrl) {
        return jdbc.queryForObject("""
            insert into chat_messages(chat_room_id,sender_id,message_type,content,image_url,created_at,is_read)
            values (?,?,?,?,?,clock_timestamp(),false) returning id
            """, Long.class, room, b.getId(), type, content, imageUrl);
    }
    private long reportId(Object input) {
        var response = call("POST", "/api/reports", a, input);
        assertThat(response.status).as("%s", response.body).isEqualTo(201);
        return ((Number) response.data().get("reportId")).longValue();
    }
    private static String admin(long id) { return "/api/admin/reports/" + id; }
    private static Map<String, Object> change(String status, long version) {
        return Map.of("status", status, "reason", "심사 사유", "expectedVersion", version, "requestId", UUID.randomUUID());
    }
    @SuppressWarnings("unchecked")
    private static List<Map<String,Object>> entries(Map<String,Object> data, String field) {
        return (List<Map<String,Object>>) data.get(field);
    }

    @SuppressWarnings("unchecked")
    Response call(String method,String path,User actor,Object body) {
        return callToken(method, path, actor == null ? null : jwt.createAccessToken(actor.getId()), body);
    }
    @SuppressWarnings("unchecked")
    Response callToken(String method,String path,String token,Object body) {
        var request=RestClient.create("http://127.0.0.1:"+port).method(org.springframework.http.HttpMethod.valueOf(method)).uri(path);
        if(token!=null)request.header("Authorization","Bearer "+token);
        if(body!=null)request.contentType(MediaType.APPLICATION_JSON).body(body);
        return request.exchange((req,res)->new Response(res.getStatusCode().value(),res.bodyTo(Map.class)));
    }
    @SuppressWarnings("unchecked") record Response(int status,Map<String,Object> body) { Map<String,Object> data(){return (Map<String,Object>)body.get("data");} }
}
