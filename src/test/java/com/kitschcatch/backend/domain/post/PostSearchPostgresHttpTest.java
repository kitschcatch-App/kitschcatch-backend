// 매 실행의 전용 PostgreSQL 스키마에서 실제 JWT HTTP 검색 계약을 검증한다.
package com.kitschcatch.backend.domain.post;

import java.sql.DriverManager;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@EnabledIfEnvironmentVariable(named = "ISSUE47_TEST_DB_URL", matches = "jdbc:postgresql:.*")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.datasource.driver-class-name=org.postgresql.Driver", "spring.jpa.hibernate.ddl-auto=create",
    "kakao.oauth.native-app-key=test-native-app-key", "app.orders.expiration-enabled=false",
    "app.payments.recovery.enabled=false", "app.s3.public-base-url=https://cdn.example.test",
    "app.s3.bucket=test-bucket", "springdoc.api-docs.enabled=true",
    "spring.jpa.properties.hibernate.generate_statistics=true"
})
@DirtiesContext
class PostSearchPostgresHttpTest extends PostSearchHttpContract {
    static final String schema = "issue47_" + UUID.randomUUID().toString().replace("-", "");
    static final String url = System.getenv("ISSUE47_TEST_DB_URL");
    static final String username = System.getenv().getOrDefault("ISSUE47_TEST_DB_USERNAME", "postgres");
    static final String password = System.getenv().getOrDefault("ISSUE47_TEST_DB_PASSWORD", "");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) throws Exception {
        try (var connection = DriverManager.getConnection(url, username, password); var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + schema);
        }
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", () -> username);
        registry.add("spring.datasource.password", () -> password);
        registry.add("spring.datasource.hikari.schema", () -> schema);
        registry.add("spring.jpa.properties.hibernate.default_schema", () -> schema);
    }

    @AfterAll
    static void dropOwnSchema() throws Exception {
        try (var connection = DriverManager.getConnection(url, username, password); var statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA " + schema + " CASCADE");
        }
    }
}
