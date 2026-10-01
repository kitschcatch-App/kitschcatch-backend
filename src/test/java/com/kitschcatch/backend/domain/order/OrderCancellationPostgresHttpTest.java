// 수동 SQL로 스냅샷 컬럼·인덱스를 만든 PostgreSQL에서 거래 후속 처리 HTTP 계약를 검증한다.
package com.kitschcatch.backend.domain.order;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@EnabledIfEnvironmentVariable(named = "ISSUE45_TEST_DB_URL", matches = "jdbc:postgresql:.*")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.datasource.driver-class-name=org.postgresql.Driver", "spring.jpa.hibernate.ddl-auto=create",
    "kakao.oauth.native-app-key=test-native-app-key", "app.orders.expiration-enabled=false",
    "app.payments.recovery.enabled=false", "app.s3.public-base-url=https://cdn.example.test",
    "app.s3.bucket=test-bucket", "springdoc.api-docs.enabled=true",
    "spring.jpa.properties.hibernate.generate_statistics=true"
})
@DirtiesContext
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OrderCancellationPostgresHttpTest extends OrderCancellationHttpContract {
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
    static void applyManualSqlInsteadOfGeneratedHistorySchema() throws Exception {
        try (var connection = database.connect(); var statement = connection.createStatement()) {
            PostgresOrderLifecycleTestDatabase.replaceGeneratedColumns(connection);
        }
    }

    @AfterAll
    static void dropIsolatedSchema() throws Exception {
        if (database != null) database.close();
    }
}
