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
    "app.s3.bucket=test-bucket", "springdoc.api-docs.enabled=true", "app.settlement.operator-user-ids=900000", "app.settlement.fee-basis-points=500",
    "spring.jpa.properties.hibernate.generate_statistics=true",
    "app.settlement.sync-enabled=true", "app.settlement.sync-delay-ms=3600000"
})
@DirtiesContext
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SettlementPostgresHttpTest extends SettlementHttpContract {
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
            try (var in = SettlementPostgresHttpTest.class.getResourceAsStream("/db/manual/116_settlement_sync.sql")) {
                statement.execute(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            }
        }
    }

    @org.springframework.beans.factory.annotation.Autowired
    com.kitschcatch.backend.domain.order.settlement.SettlementSyncWorker worker;

    @org.junit.jupiter.api.Test
    void workerRecoversAnUncertainPayoutUsingLookupOnly() {
        var order = confirmedOrder();
        var waiting = get("/api/orders/" + order.orderId() + "/settlement", sellerToken);
        String id = (String) waiting.data().get("settlementId");
        worker.synchronize();
        org.mockito.Mockito.verify(gateway, org.mockito.Mockito.never()).request(org.mockito.ArgumentMatchers.any());
        org.mockito.Mockito.when(gateway.request(org.mockito.ArgumentMatchers.any()))
            .thenThrow(new IllegalStateException("network"));
        assertError(execute(order, operatorToken), 502, "SETTLEMENT_006");
        var path = "/api/admin/settlements/" + id;
        assertError(get(path, buyerToken), 403, "SETTLEMENT_004");
        org.assertj.core.api.Assertions.assertThat(request("POST", path + "/refresh", operatorToken, null).status())
            .isEqualTo(202);
        org.mockito.Mockito.when(gateway.lookup(org.mockito.ArgumentMatchers.any())).thenReturn(complete(id));
        worker.synchronize();
        worker.synchronize();
        org.assertj.core.api.Assertions.assertThat(get(path, operatorToken).data())
            .containsEntry("settlementStatus", "COMPLETED").containsEntry("syncState", "DONE");
        org.mockito.Mockito.verify(gateway, org.mockito.Mockito.times(1)).request(org.mockito.ArgumentMatchers.any());
        org.mockito.Mockito.verify(gateway, org.mockito.Mockito.times(1)).lookup(org.mockito.ArgumentMatchers.any());
    }

    @AfterAll
    static void dropIsolatedSchema() throws Exception {
        if (database != null) database.close();
    }
}
