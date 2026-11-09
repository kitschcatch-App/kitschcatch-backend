// 기존 알림 스키마를 확장한 PostgreSQL에서 거래 알림과 배포 계약을 검증한다.
package com.kitschcatch.backend.domain.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.json.JsonMapper;

@EnabledIfEnvironmentVariable(named = "ISSUE45_TEST_DB_URL", matches = "jdbc:postgresql:.*")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.datasource.driver-class-name=org.postgresql.Driver", "spring.jpa.hibernate.ddl-auto=create",
    "kakao.oauth.native-app-key=test-native-app-key", "app.orders.expiration-enabled=false",
    "app.payments.recovery.enabled=false", "app.notifications.trade-enabled=true",
    "app.refunds.operator-user-ids=900000", "app.settlement.operator-user-ids=900000",
    "app.settlement.fee-basis-points=500"
})
@DirtiesContext
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TradeNotificationPostgresHttpTest extends TradeNotificationHttpContract {
    private static PostgresOrderLifecycleTestDatabase database;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) throws Exception {
        database = new PostgresOrderLifecycleTestDatabase();
        registry.add("spring.datasource.url", () -> database.url);
        registry.add("spring.datasource.username", () -> database.username);
        registry.add("spring.datasource.password", () -> database.password);
        registry.add("spring.datasource.hikari.schema", () -> database.schema);
        registry.add("spring.jpa.properties.hibernate.default_schema", () -> database.schema);
    }

    @BeforeAll
    void applyManualSqlToLegacyTables() throws Exception {
        try (var connection=database.connect(); var sql=connection.createStatement()) {
            PostgresOrderLifecycleTestDatabase.replaceGeneratedColumns(connection);
            sql.execute("ALTER TABLE orders DROP COLUMN shipment_revision");
            sql.execute("DROP TABLE push_attempts, push_deliveries, device_tokens, notifications");
            sql.execute(migration("050_notifications.sql"));
            sql.execute(migration("050_notifications.sql"));
            sql.execute("""
                ALTER TABLE notifications DROP CONSTRAINT notifications_type_check,
                    ADD CONSTRAINT notifications_type_check CHECK (type IN
                        ('CHAT_MESSAGE','PAYMENT_SUCCESS','PAYMENT_CANCELED'))
                """);
            sql.execute("""
                INSERT INTO users(id,nickname,email,auth_provider,provider_user_id,created_at)
                VALUES(800000,'기존 사용자','legacy-notice@example.test','KAKAO','legacy-notice',CURRENT_TIMESTAMP)
                """);
            sql.execute("""
                INSERT INTO notifications(user_id,event_key,type,title,target_id,created_at,read_at)
                VALUES(800000,'legacy-event','PAYMENT_CANCELED','기존 결제 취소','ORD-LEGACY',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                """);
            sql.execute(migration("069_trade_notifications.sql"));
            sql.execute(migration("069_trade_notifications.sql"));
            try (var row=sql.executeQuery("SELECT title,read_at FROM notifications WHERE event_key='legacy-event'")) {
                assertThat(row.next()).isTrue();
                assertThat(row.getString("title")).isEqualTo("기존 결제 취소");
                assertThat(row.getTimestamp("read_at")).isNotNull();
                assertThat(row.next()).isFalse();
            }
            var contract=Files.readAllLines(Path.of("deploy/sydney/schema-contract.jsonl"));
            var mapper=JsonMapper.builder().build();
            var expectedCheck=contract.stream().filter(row->row.startsWith("[\"constraint\",\"notifications\",\"notifications_type_check\""))
                .findFirst().orElseThrow();
            try (var row=sql.executeQuery("""
                SELECT pg_get_constraintdef(oid),convalidated FROM pg_constraint
                WHERE conrelid='notifications'::regclass AND conname='notifications_type_check'
                """)) {
                assertThat(row.next()).isTrue();
                assertThat(List.of("constraint","notifications","notifications_type_check",row.getString(1),row.getBoolean(2)))
                    .isEqualTo(mapper.readValue(expectedCheck,List.class));
            }
            assertThatThrownBy(()->sql.execute("UPDATE notifications SET type='UNKNOWN_NOTIFICATION'"))
                .isInstanceOfSatisfying(SQLException.class, error->assertThat(error.getSQLState()).isEqualTo("23514"));
        }
    }

    @AfterAll
    static void dropIsolatedSchema() throws Exception {
        if (database != null) database.close();
    }

    private String migration(String filename) throws Exception {
        try (var stream=getClass().getResourceAsStream("/db/manual/"+filename)) {
            return new String(stream.readAllBytes(),StandardCharsets.UTF_8);
        }
    }
}
