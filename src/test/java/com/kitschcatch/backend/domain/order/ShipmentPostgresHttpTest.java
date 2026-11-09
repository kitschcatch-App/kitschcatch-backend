// 수동 SQL로 스냅샷 컬럼·인덱스를 만든 PostgreSQL에서 거래 후속 처리 HTTP 계약를 검증한다.
package com.kitschcatch.backend.domain.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.anyString;

import com.kitschcatch.backend.domain.order.tracking.TrackingProvider;
import com.kitschcatch.backend.domain.order.tracking.TrackingWorker;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
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
    "spring.jpa.properties.hibernate.generate_statistics=true",
    "app.shipment.tracking.enabled=true", "app.shipment.tracking.delay-ms=3600000",
    "app.shipment.tracking.notify-enabled=true", "app.notifications.trade-enabled=true"
})
@DirtiesContext
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ShipmentPostgresHttpTest extends ShipmentHttpContract {
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
            try (var in = ShipmentPostgresHttpTest.class.getResourceAsStream("/db/manual/117_shipment_tracking.sql")) {
                statement.execute(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            }
        }
    }

    @MockitoBean TrackingProvider provider;
    @Autowired TrackingWorker worker;

    @Override
    @org.junit.jupiter.api.BeforeEach
    void resetData() {
        jdbc.update("delete from notifications");
        super.resetData();
        reset(provider);
    }

    @Test
    void tracksCurrentRevisionAndIgnoresPreviousCallbacks() {
        var order = order(buyer, seller, false);
        approve(order);
        assertThat(ship(order, sellerToken).status()).isEqualTo(200);
        String path = "/api/orders/" + order.orderId() + "/shipment";
        assertError(get(path + "/tracking", strangerToken), 403, "ORDER_004");
        var callback = jdbc.queryForObject("select callback_token from shipment_tracking", UUID.class);
        var event = new TrackingProvider.Event(TrackingProvider.Status.IN_TRANSIT, OffsetDateTime.parse("2026-11-09T05:00:00Z"));
        when(provider.lookup("kr.cjlogistics", "123456789012")).thenReturn(new TrackingProvider.Snapshot(event, List.of(event, event)));
        worker.run();
        assertThat(get(path + "/tracking", buyerToken).data()).containsEntry("status", "IN_TRANSIT");
        assertThat(jdbc.queryForObject("select count(*) from shipment_tracking_events", Long.class)).isEqualTo(1L);
        assertThat(request("PATCH", path, sellerToken, Map.of("carrierCode", "HANJIN", "trackingNumber", "987654321098")).status())
            .isEqualTo(200);
        request("POST", "/api/shipment-tracking/webhooks/" + callback, null,
            Map.of("carrierId", "kr.cjlogistics", "trackingNumber", "123456789012"));
        var delivered = new TrackingProvider.Event(TrackingProvider.Status.DELIVERED, event.occurredAt().plusHours(1));
        when(provider.lookup("kr.hanjin", "987654321098")).thenReturn(new TrackingProvider.Snapshot(delivered, List.of(delivered)));
        worker.run();
        worker.run();
        assertThat(get(path + "/tracking", sellerToken).data())
            .containsEntry("status", "DELIVERED").containsEntry("shipmentRevision", 2);
        assertThat(jdbc.queryForObject("select order_status from orders", String.class)).isEqualTo("PAID");
        assertThat(jdbc.queryForObject("""
            select count(*) from notifications where user_id=? and type='SHIPMENT_DELIVERED'
            """, Long.class, buyer.getId())).isEqualTo(1L);
        assertThat(jdbc.queryForObject("""
            select count(*) from notifications where user_id=? and type='SHIPMENT_DELIVERED'
            """, Long.class, seller.getId())).isZero();
        verify(provider, times(2)).lookup(anyString(), anyString());
    }

    @Test
    void retriesWebhookLookupAfterWorkerLeaseExpires() {
        var order = order(buyer, seller, false);
        approve(order);
        assertThat(ship(order, sellerToken).status()).isEqualTo(200);
        var moving = new TrackingProvider.Event(TrackingProvider.Status.IN_TRANSIT,
            OffsetDateTime.parse("2026-11-09T05:00:00Z"));
        var delivered = new TrackingProvider.Event(TrackingProvider.Status.DELIVERED, moving.occurredAt().plusHours(1));
        when(provider.lookup("kr.cjlogistics", "123456789012"))
            .thenReturn(new TrackingProvider.Snapshot(moving, List.of(moving)))
            .thenReturn(new TrackingProvider.Snapshot(delivered, List.of(delivered)));
        worker.run();
        // 기존 작업자가 콜백을 꺼낸 뒤 결과를 쓰기 전에 종료된 상태를 재현한다.
        jdbc.update("""
            update shipment_tracking set pending=false, lease_token=?, lease_until=now()-interval '1 second',
                next_check_at=now()-interval '1 minute'
            """, UUID.randomUUID());
        worker.run();
        assertThat(get("/api/orders/" + order.orderId() + "/shipment/tracking", buyerToken).data())
            .containsEntry("status", "DELIVERED");
        verify(provider, times(2)).lookup(anyString(), anyString());
    }

    @AfterAll
    static void dropIsolatedSchema() throws Exception {
        if (database != null) database.close();
    }
}
