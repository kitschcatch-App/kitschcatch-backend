// 수동 후기 SQL을 적용한 PostgreSQL에서 실제 HTTP 계약과 경합을 검증한다.
package com.kitschcatch.backend.domain.review;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;

@EnabledIfEnvironmentVariable(named = "ISSUE56_TEST_DB_URL", matches = "jdbc:postgresql:.*")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.datasource.driver-class-name=org.postgresql.Driver", "spring.jpa.hibernate.ddl-auto=create",
    "kakao.oauth.native-app-key=test", "app.orders.expiration-enabled=false", "app.payments.recovery.enabled=false",
    "spring.jpa.properties.hibernate.generate_statistics=true"
})
@DirtiesContext
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TransactionReviewPostgresHttpTest extends TransactionReviewHttpContract {
    static PostgresReviewTestDatabase database;
    @DynamicPropertySource static void databaseProperties(DynamicPropertyRegistry registry) throws Exception {
        database = new PostgresReviewTestDatabase();
        registry.add("spring.datasource.url", () -> database.url);
        registry.add("spring.datasource.username", () -> database.username);
        registry.add("spring.datasource.password", () -> database.password);
        registry.add("spring.datasource.hikari.schema", () -> database.schema);
        registry.add("spring.jpa.properties.hibernate.default_schema", () -> database.schema);
    }
    @BeforeAll void applyManualSql() throws Exception {
        try (var c = database.connect(); var s = c.createStatement()) {
            s.execute("DROP TABLE transaction_reviews");
            PostgresReviewTestDatabase.migrate(c); PostgresReviewTestDatabase.migrate(c);
        }
    }
    @AfterAll void dropSchema() throws Exception { if (database != null) database.close(); }
}
