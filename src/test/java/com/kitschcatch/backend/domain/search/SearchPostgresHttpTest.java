// 수동 스키마를 적용한 격리 PostgreSQL에서 검색어 HTTP·동시성·기간 계약을 검증한다.
package com.kitschcatch.backend.domain.search;

import static org.assertj.core.api.Assertions.*;
import java.time.*;
import java.util.*;
import java.sql.DriverManager;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.*;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;
import org.springframework.http.*;
import com.kitschcatch.backend.domain.user.entity.*;
import com.kitschcatch.backend.domain.user.repository.UserRepository;
import com.kitschcatch.backend.global.security.JwtTokenProvider;

@EnabledIfEnvironmentVariable(named="ISSUE79_TEST_DB_URL",matches="jdbc:postgresql:.*")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
 "spring.datasource.driver-class-name=org.postgresql.Driver","spring.jpa.hibernate.ddl-auto=create",
 "kakao.oauth.native-app-key=test", "app.orders.expiration-enabled=false","app.payments.recovery.enabled=false",
 "app.search.cleanup-enabled=false"})
@DirtiesContext
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SearchPostgresHttpTest {
    static final String schema="search79_"+UUID.randomUUID().toString().replace("-","");
    static final String url=System.getenv("ISSUE79_TEST_DB_URL");
    static final String username=System.getenv().getOrDefault("ISSUE79_TEST_DB_USERNAME","postgres");
    static final String password=System.getenv().getOrDefault("ISSUE79_TEST_DB_PASSWORD","");
    static class MutableClock extends Clock {
        volatile Instant now=Instant.parse("2026-11-04T14:59:59Z");
        public ZoneId getZone(){return ZoneOffset.UTC;}
        public Clock withZone(ZoneId zone){return Clock.fixed(now,zone);}
        public Instant instant(){return now;}
    }
    @TestBean(name="searchClock",methodName="testClock") Clock clock;
    static Clock testClock(){return new MutableClock();}
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) throws Exception {
        try(var c=DriverManager.getConnection(url,username,password);var s=c.createStatement()){s.execute("CREATE SCHEMA "+schema);}
        registry.add("spring.datasource.url",()->url); registry.add("spring.datasource.username",()->username);
        registry.add("spring.datasource.password",()->password); registry.add("spring.datasource.hikari.schema",()->schema);
        registry.add("spring.jpa.properties.hibernate.default_schema",()->schema);
    }
    @Autowired JdbcTemplate jdbc;
    @Autowired SearchService service;
    @Autowired UserRepository users;
    @Autowired JwtTokenProvider jwt;
    @LocalServerPort int port;
    User a,b,c;
    @BeforeAll void migrate() {
        jdbc.execute("DROP TABLE recent_searches,search_daily_users,related_search_keywords");
        sql("079_search_keywords.sql"); sql("079_search_keywords.sql");
        sql("079_search_related_seed.sql"); sql("079_search_related_seed.sql");
    }
    void sql(String file) {
        try(var in=getClass().getResourceAsStream("/db/manual/"+file)) { jdbc.execute(new String(in.readAllBytes(),StandardCharsets.UTF_8)); }
        catch(Exception e){throw new IllegalStateException(e);}
    }
    @BeforeEach void reset(){
        jdbc.update("DELETE FROM recent_searches");jdbc.update("DELETE FROM search_daily_users");
        ((MutableClock)clock).now=Instant.parse("2026-11-04T14:59:59Z");
        a=user();b=user();c=user();
    }
    User user(){String id=UUID.randomUUID().toString();return users.saveAndFlush(User.builder().nickname(id.substring(0,8)).authProvider(AuthProvider.KAKAO).providerUserId(id).build());}
    record Response(int status,Map<String,Object> body){ @SuppressWarnings("unchecked") Map<String,Object> data(){return (Map<String,Object>)body.get("data");}}
    Response call(String method,String path,User user,Object body){
        var r=RestClient.create("http://localhost:"+port).method(HttpMethod.valueOf(method)).uri(path);
        if(user!=null) r.header("Authorization","Bearer "+jwt.createAccessToken(user.getId()));
        if(body!=null)r.contentType(org.springframework.http.MediaType.APPLICATION_JSON).body(body);
        return r.exchange((req,res)->new Response(res.getStatusCode().value(),res.bodyTo(Map.class)));
    }
    @Test void recentOwnershipCapAndDailyDedup(){
        var first=call("POST","/api/search/records",a,Map.of("keyword"," Pokemon "));
        assertThat(first.status()).isEqualTo(200);
        assertThat(call("POST","/api/search/records",a,Map.of("keyword","pokemon")).data().get("id")).isEqualTo(first.data().get("id"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM search_daily_users",Long.class)).isEqualTo(1);
        call("DELETE","/api/search/recent/"+first.data().get("id"),b,null);
        assertThat(service.recent(a.getId()).items()).hasSize(1);
        call("DELETE","/api/search/recent",a,null);
        service.record(a.getId(),"pokemon");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM search_daily_users",Long.class)).isEqualTo(1);
        for(int i=0;i<23;i++)service.record(a.getId(),"검색"+i);
        assertThat(service.recent(a.getId()).items()).hasSize(20);
        assertThat(service.recent(a.getId()).items().getFirst().keyword()).isEqualTo("검색22");
    }
    @Test void popularityDatesMinimumUsersAndExpiry(){
        service.record(a.getId(),"피규어");service.record(b.getId(),"피규어");
        assertThat(service.popular(a.getId(),10).items()).isEmpty();service.record(c.getId(),"피규어");
        assertThat(call("GET","/api/search/popular",a,null).status()).isEqualTo(200);
        assertThat(service.popular(a.getId(),10).items().getFirst().keyword()).isEqualTo("피규어");
        ((MutableClock)clock).now=Instant.parse("2026-11-04T15:00:00Z");service.record(a.getId(),"피규어");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM search_daily_users",Long.class)).isEqualTo(4);
        ((MutableClock)clock).now=Instant.parse("2026-11-11T15:00:00Z");
        assertThat(service.popular(a.getId(),10).items()).isEmpty();assertThat(service.cleanupBatch()).isEqualTo(4);
    }
    @Test void relatedValidationAndAuthentication(){
        assertThat(service.related(a.getId(),"포켓몬",10).items()).hasSize(3);
        assertThat(service.related(a.getId(),"피카츄",10).items()).isEmpty();
        for(String path:List.of("/api/search/recent","/api/search/popular","/api/search/related?keyword=x"))
            assertThat(call("GET",path,null,null).status()).isEqualTo(401);
        assertThat(call("POST","/api/search/records",a,Map.of("keyword"," ")).status()).isEqualTo(400);
        assertThat(call("GET","/api/search/popular?limit=0",a,null).status()).isEqualTo(400);
        assertThat(call("GET","/api/search/related?keyword=포켓몬",a,null).status()).isEqualTo(200);
        assertThat(call("DELETE","/api/search/recent",a,null).status()).isEqualTo(200);
    }
    @Test void concurrentRecordsAndWithdrawal() throws Exception {
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(6)){
            var work=new ArrayList<java.util.concurrent.Callable<Object>>();
            for(int i=0;i<12;i++)work.add(()->service.record(a.getId(),"피규어"));
            for(var f:pool.invokeAll(work))f.get();
        }
        assertThat(service.recent(a.getId()).items()).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM search_daily_users",Long.class)).isEqualTo(1);
        var response=call("DELETE","/api/users/me",a,null);
        assertThat(response.status()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM recent_searches",Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM search_daily_users",Long.class)).isZero();
        assertThatThrownBy(()->service.record(a.getId(),"다시")).isInstanceOf(com.kitschcatch.backend.global.exception.BusinessException.class);
    }
    @Test void failureRollsBackBothRecords() {
        jdbc.execute("ALTER TABLE search_daily_users ADD CONSTRAINT reject_test_word CHECK(normalized_keyword <> 'rollback')");
        try {
            assertThatThrownBy(()->service.record(a.getId(),"rollback")).isInstanceOf(RuntimeException.class);
            assertThat(service.recent(a.getId()).items()).isEmpty();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM search_daily_users",Long.class)).isZero();
        } finally { jdbc.execute("ALTER TABLE search_daily_users DROP CONSTRAINT reject_test_word"); }
    }
    @Test void productGetIsReadOnlyAndRelatedActivationIsRespected() {
        assertThat(call("GET","/api/posts?keyword=피규어",a,null).status()).isEqualTo(200);
        assertThat(service.recent(a.getId()).items()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM search_daily_users",Long.class)).isZero();
        jdbc.update("UPDATE related_search_keywords SET active=false WHERE target_keyword='피카츄'");
        try { assertThat(service.related(a.getId(),"포켓몬",10).items()).hasSize(2); }
        finally { jdbc.update("UPDATE related_search_keywords SET active=true"); }
    }
    @Autowired com.kitschcatch.backend.domain.user.service.UserWithdrawalService withdrawal;
    @Test void recordAndWithdrawalRaceCannotLeaveHistory() throws Exception {
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var gate=new java.util.concurrent.CyclicBarrier(2);
            var record=pool.submit(()->{gate.await(10,java.util.concurrent.TimeUnit.SECONDS);
                try {service.record(a.getId(),"경쟁");} catch(com.kitschcatch.backend.global.exception.BusinessException expected) { }
                return null;});
            var remove=pool.submit(()->{gate.await(10,java.util.concurrent.TimeUnit.SECONDS);withdrawal.withdraw(a.getId());return null;});
            record.get(20,java.util.concurrent.TimeUnit.SECONDS);remove.get(20,java.util.concurrent.TimeUnit.SECONDS);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM recent_searches WHERE user_id=?",Long.class,a.getId())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM search_daily_users WHERE user_id=?",Long.class,a.getId())).isZero();
    }
    @AfterAll void cleanup() throws Exception {try(var c=DriverManager.getConnection(url,username,password);var s=c.createStatement()){s.execute("DROP SCHEMA "+schema+" CASCADE");}}
}
