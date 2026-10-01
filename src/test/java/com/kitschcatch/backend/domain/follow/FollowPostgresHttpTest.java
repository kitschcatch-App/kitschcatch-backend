// 수동 SQL로 생성한 PostgreSQL에서 HTTP와 반대 방향 동시성까지 검증한다.
package com.kitschcatch.backend.domain.follow;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@EnabledIfEnvironmentVariable(named = "ISSUE52_TEST_DB_URL", matches = "jdbc:postgresql:.*")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.datasource.driver-class-name=org.postgresql.Driver", "spring.jpa.hibernate.ddl-auto=create",
    "kakao.oauth.native-app-key=test-native-app-key",
    "app.orders.expiration-enabled=false", "app.payments.recovery.enabled=false",
    "springdoc.api-docs.enabled=true", "spring.jpa.properties.hibernate.generate_statistics=true",
    "app.s3.public-base-url=https://images.example.test"
})
@DirtiesContext
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FollowPostgresHttpTest extends FollowHttpContract {
    private static PostgresFollowTestDatabase database;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws Exception {
        database = new PostgresFollowTestDatabase();
        registry.add("spring.datasource.url", () -> database.url);
        registry.add("spring.datasource.username", () -> database.username);
        registry.add("spring.datasource.password", () -> database.password);
        registry.add("spring.datasource.hikari.schema", () -> database.schema);
        registry.add("spring.jpa.properties.hibernate.default_schema", () -> database.schema);
    }
    @BeforeAll
    static void useManualSql() throws Exception {
        try (var connection = database.connect(); var statement = connection.createStatement()) {
            statement.execute("DROP TABLE user_follows");
            PostgresFollowTestDatabase.migrate(connection);
            PostgresFollowTestDatabase.migrate(connection);
        }
    }
    @AfterAll
    static void cleanup() throws Exception { if (database != null) database.close(); }
}
