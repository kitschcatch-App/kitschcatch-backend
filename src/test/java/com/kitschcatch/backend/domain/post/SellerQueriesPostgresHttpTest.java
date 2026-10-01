// 전용 PostgreSQL DB에서 판매자 상품·채팅 조회 HTTP 계약을 검증한다.
package com.kitschcatch.backend.domain.post;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
@EnabledIfEnvironmentVariable(named="ISSUE48_TEST_DB_URL", matches="jdbc:postgresql:.*")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.datasource.driver-class-name=org.postgresql.Driver", "spring.jpa.hibernate.ddl-auto=create-drop",
    "kakao.oauth.native-app-key=test-native-app-key", "app.orders.expiration-enabled=false",
    "app.payments.recovery.enabled=false", "app.s3.public-base-url=https://cdn.example.test",
    "app.s3.bucket=test-bucket", "spring.jpa.properties.hibernate.generate_statistics=true"
})
class SellerQueriesPostgresHttpTest extends SellerQueriesHttpContract {
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("ISSUE48_TEST_DB_URL"));
        registry.add("spring.datasource.username", () -> System.getenv().getOrDefault("ISSUE48_TEST_DB_USERNAME", "postgres"));
        registry.add("spring.datasource.password", () -> System.getenv().getOrDefault("ISSUE48_TEST_DB_PASSWORD", ""));
    }
}
