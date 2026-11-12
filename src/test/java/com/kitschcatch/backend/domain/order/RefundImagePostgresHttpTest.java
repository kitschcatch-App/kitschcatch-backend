// 실제 반품 사진 SQL의 소유권·검증 제약과 PostgreSQL HTTP 흐름을 검증한다.
package com.kitschcatch.backend.domain.order;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@EnabledIfEnvironmentVariable(named = "ISSUE45_TEST_DB_URL", matches = "jdbc:postgresql:.*")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.datasource.driver-class-name=org.postgresql.Driver", "spring.jpa.hibernate.ddl-auto=create",
    "kakao.oauth.native-app-key=test-native-app-key", "app.orders.expiration-enabled=false",
    "app.payments.recovery.enabled=false", "app.refund-images.cleanup-enabled=false",
    "app.refunds.operator-user-ids=900000", "app.s3.bucket=refund-test-bucket"
})
@Import(RefundImageHttpContract.SigningConfig.class)
@DirtiesContext
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RefundImagePostgresHttpTest extends RefundImageHttpContract {
    private static PostgresOrderLifecycleTestDatabase database;

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) throws Exception {
        database = new PostgresOrderLifecycleTestDatabase();
        registry.add("spring.datasource.url", () -> database.url);
        registry.add("spring.datasource.username", () -> database.username);
        registry.add("spring.datasource.password", () -> database.password);
        registry.add("spring.datasource.hikari.schema", () -> database.schema);
        registry.add("spring.jpa.properties.hibernate.default_schema", () -> database.schema);
    }

    @BeforeAll
    static void migrate() throws Exception {
        try (var connection = database.connect(); var statement = connection.createStatement();
             var script = RefundImagePostgresHttpTest.class.getResourceAsStream("/db/manual/097_refund_images.sql")) {
            PostgresOrderLifecycleTestDatabase.replaceGeneratedColumns(connection);
            statement.execute("DROP TABLE refund_images");
            var sql = new String(script.readAllBytes(), StandardCharsets.UTF_8);
            statement.execute(sql);
            statement.execute(sql);
        }
    }

    @AfterAll
    static void dropSchema() throws Exception {
        if (database != null) database.close();
    }

    @Test
    void sqlRejectsOtherBuyersInvalidEvidenceAndMismatchedRefund() throws Exception {
        var order = order(buyer, seller, false);
        long id = jdbc.queryForObject("SELECT id FROM orders WHERE order_number = ?", Long.class, order.orderId());
        var nonce = "879ad4f1-4313-467e-bbc6-e29bdf99ac0e";
        try (var connection = database.connect(); var statement = connection.createStatement()) {
            String insert = """
                INSERT INTO refund_images(order_id, uploader_id, temporary_key, object_key, content_type, expected_size,
                    created_at, expires_at, cleanup_at, state)
                VALUES (%d, %%d, 'refund-uploads/%d/%s', 'refund-images/%d/%s.png', 'image/png', 10,
                    now(), now()+interval '1 day', now()+interval '1 day', 'PENDING')
                """.formatted(id, id, nonce, id, nonce);
            assertThatThrownBy(() -> statement.execute(insert.formatted(seller.getId())))
                .isInstanceOf(SQLException.class).hasMessageContaining("fk_refund_images_buyer");
            statement.execute(insert.formatted(buyer.getId()));
            assertThatThrownBy(() -> statement.execute("UPDATE refund_images SET state='READY'"))
                .isInstanceOf(SQLException.class).hasMessageContaining("ck_refund_images_verification");
            assertThatThrownBy(() -> statement.execute("""
                UPDATE refund_images SET state='ATTACHED', width=3, height=2, sha256=repeat('a',64),
                  refund_id='REF-missing', sort_order=0
                """))
                .isInstanceOf(SQLException.class).hasMessageContaining("fk_refund_images_refund");
        }
    }
}
