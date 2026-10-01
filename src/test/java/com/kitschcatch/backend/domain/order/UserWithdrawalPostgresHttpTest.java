// 격리한 PostgreSQL 스키마에 실제 수동 SQL을 반복 적용하고 동일 탈퇴 계약을 검증한다.
package com.kitschcatch.backend.domain.order;

import java.sql.DriverManager;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.annotation.DirtiesContext;

@EnabledIfEnvironmentVariable(named="ISSUE57_TEST_DB_URL",matches="jdbc:postgresql:.*")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "spring.datasource.driver-class-name=org.postgresql.Driver","spring.jpa.hibernate.ddl-auto=create",
    "kakao.oauth.native-app-key=test-native-app-key","app.orders.expiration-enabled=false",
    "app.payments.recovery.enabled=false","app.s3.public-base-url=https://cdn.example.test","springdoc.api-docs.enabled=true"
})
@DirtiesContext
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class UserWithdrawalPostgresHttpTest extends UserWithdrawalHttpContract {
    static String schema="issue57_"+UUID.randomUUID().toString().replace("-","");
    static String url=System.getenv("ISSUE57_TEST_DB_URL");
    static String username=System.getenv().getOrDefault("ISSUE57_TEST_DB_USERNAME","postgres");
    static String password=System.getenv().getOrDefault("ISSUE57_TEST_DB_PASSWORD","");
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) throws Exception {
        try(var c=DriverManager.getConnection(url,username,password);var s=c.createStatement()) {
            s.execute("create schema "+schema);
        }
        registry.add("spring.datasource.url",()->url);
        registry.add("spring.datasource.username",()->username);
        registry.add("spring.datasource.password",()->password);
        registry.add("spring.datasource.hikari.schema",()->schema);
        registry.add("spring.jpa.properties.hibernate.default_schema",()->schema);
    }
    @BeforeAll void applyManualSql() throws Exception {
        var existing=users.saveAndFlush(com.kitschcatch.backend.domain.user.entity.User.builder()
            .nickname("기존 회원").email("migration@example.test")
            .authProvider(com.kitschcatch.backend.domain.user.entity.AuthProvider.KAKAO).providerUserId("migration-test").build());
        var before=jdbc.queryForMap("select * from users where id=?",existing.getId());
        try(var c=DriverManager.getConnection(url,username,password);var s=c.createStatement();
            var input=getClass().getResourceAsStream("/db/manual/057_user_withdrawal.sql")) {
            c.setSchema(schema);
            s.execute("alter table users drop column withdrawn_at");
            String sql=new String(input.readAllBytes(),StandardCharsets.UTF_8);
            s.execute(sql);s.execute(sql);
            org.assertj.core.api.Assertions.assertThat(jdbc.queryForMap("select * from users where id=?",existing.getId())).isEqualTo(before);
            org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("select data_type from information_schema.columns where table_schema=? and table_name='users' and column_name='withdrawn_at'",String.class,schema)).isEqualTo("timestamp with time zone");
        }
    }
    @AfterAll void cleanup() throws Exception {
        try(var c=DriverManager.getConnection(url,username,password);var s=c.createStatement()) {
            s.execute("drop schema "+schema+" cascade");
        }
    }
}
