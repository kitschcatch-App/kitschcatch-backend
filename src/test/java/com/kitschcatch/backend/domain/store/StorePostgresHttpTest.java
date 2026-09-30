// 수동 SQL로 생성한 PostgreSQL 테이블에서 매장 HTTP 계약 전체를 검증한다.
package com.kitschcatch.backend.domain.store;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@EnabledIfEnvironmentVariable(named = "ISSUE39_TEST_DB_URL", matches = "jdbc:postgresql:.*")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.datasource.driver-class-name=org.postgresql.Driver",
    "spring.jpa.hibernate.ddl-auto=create", "kakao.oauth.native-app-key=test-native-app-key",
    "app.orders.expiration-enabled=false", "app.payments.recovery.enabled=false",
    "springdoc.api-docs.enabled=true"
})
@DirtiesContext
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StorePostgresHttpTest extends StoreHttpContract {
    private static PostgresStoreTestDatabase database;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) throws Exception {
        database = new PostgresStoreTestDatabase();
        registry.add("spring.datasource.url", () -> database.url);
        registry.add("spring.datasource.username", () -> database.username);
        registry.add("spring.datasource.password", () -> database.password);
        registry.add("spring.datasource.hikari.schema", () -> database.schema);
        registry.add("spring.jpa.properties.hibernate.default_schema", () -> database.schema);
    }

    @BeforeAll
    static void replaceGeneratedStoreTablesWithManualSql() throws Exception {
        try (var connection = database.connect(); var statement = connection.createStatement()) {
            statement.execute("DROP TABLE store_business_hours, stores");
            PostgresStoreTestDatabase.migrate(connection);
            PostgresStoreTestDatabase.migrate(connection);
        }
    }

    @AfterAll
    static void dropIsolatedSchema() throws Exception {
        if (database != null) database.close();
    }
}
