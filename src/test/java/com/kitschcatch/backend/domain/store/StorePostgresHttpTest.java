// 수동 SQL로 생성한 PostgreSQL 테이블에서 매장 HTTP 계약 전체를 검증한다.
package com.kitschcatch.backend.domain.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mockingDetails;

import com.kitschcatch.backend.domain.store.repository.StoreImageRepository;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@EnabledIfEnvironmentVariable(named = "ISSUE39_TEST_DB_URL", matches = "jdbc:postgresql:.*")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.datasource.driver-class-name=org.postgresql.Driver",
    "spring.jpa.hibernate.ddl-auto=create", "kakao.oauth.native-app-key=test-native-app-key",
    "app.orders.expiration-enabled=false", "app.payments.recovery.enabled=false",
    "springdoc.api-docs.enabled=true", "app.s3.bucket=store-test-bucket", "app.s3.download-url-ttl=12m"
})
@DirtiesContext
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StorePostgresHttpTest extends StoreHttpContract {
    private static PostgresStoreTestDatabase database;
    @MockitoSpyBean StoreImageRepository images;

    @Test
    void detailReadsOneSnapshotWhenContentChangesBetweenQueries() {
        long id = store("동시 갱신 검증", "서울", 0, 0).getId();
        jdbc.update("UPDATE stores SET description = '이전 소개' WHERE id = ?", id);
        jdbc.update("INSERT INTO store_amenities(store_id,code,status) VALUES (?,'PARKING','UNAVAILABLE')", id);
        var committed = new AtomicBoolean();
        doAnswer(invocation -> {
            if (committed.compareAndSet(false, true)) {
                try (var writer = database.connect()) {
                    writer.setAutoCommit(false);
                    try (var update = writer.prepareStatement("UPDATE stores SET description = '새 소개' WHERE id = ?");
                         var amenity = writer.prepareStatement(
                             "UPDATE store_amenities SET status = 'AVAILABLE' WHERE store_id = ? AND code = 'PARKING'")) {
                        update.setLong(1, id);
                        amenity.setLong(1, id);
                        update.executeUpdate();
                        amenity.executeUpdate();
                        writer.commit();
                    }
                }
            }
            return mockingDetails(images).getMockCreationSettings().getDefaultAnswer().answer(invocation);
        }).when(images).findByStoreIdOrderBySortOrder(eq(id));

        var before = get("/api/stores/" + id);
        assertThat(before.status()).isEqualTo(200);
        assertThat(committed).isTrue();
        assertThat(before.data()).containsEntry("description", "이전 소개");
        assertThat(before.rows("amenities").getFirst()).containsEntry("status", "UNAVAILABLE");
        var after = get("/api/stores/" + id);
        assertThat(after.data()).containsEntry("description", "새 소개");
        assertThat(after.rows("amenities").getFirst()).containsEntry("status", "AVAILABLE");
    }

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
            statement.execute("DROP TABLE store_review_images, store_reviews, store_amenities, store_images, store_favorites, store_business_hours, stores");
            PostgresStoreTestDatabase.migrate(connection);
            PostgresStoreTestDatabase.migrate(connection);
            PostgresStoreTestDatabase.migrateFavorites(connection);
            PostgresStoreTestDatabase.migrateDetails(connection);
            PostgresStoreTestDatabase.migrateReviews(connection);
            PostgresStoreTestDatabase.migrateReviewImages(connection);
        }
    }

    @AfterAll
    static void dropIsolatedSchema() throws Exception {
        if (database != null) database.close();
    }
}
