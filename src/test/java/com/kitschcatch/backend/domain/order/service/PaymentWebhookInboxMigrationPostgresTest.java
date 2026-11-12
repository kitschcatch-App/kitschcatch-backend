// 기존 웹훅 수신함을 보존하는 126 유일 제약의 적용과 롤백을 검증한다.
package com.kitschcatch.backend.domain.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named = "ISSUE54_TEST_DB_URL", matches = ".+")
class PaymentWebhookInboxMigrationPostgresTest {
    @Test
    void migrationAddsConstraintAndCanBeReappliedWithoutChangingExistingRows() throws Exception {
        try (var database = new Issue54PostgresDatabase(); var connection = database.connect()) {
            legacyInbox(connection);
            migrate(connection);
            migrate(connection);

            assertThat(rowCount(connection)).isEqualTo(3);
            assertThat(hasEventConstraint(connection)).isTrue();
        }
    }

    @Test
    void migrationLeavesHistoricalRowsUntouchedWhenExistingDuplicatesNeedReview() throws Exception {
        try (var database = new Issue54PostgresDatabase(); var connection = database.connect()) {
            legacyInbox(connection);
            execute(connection, "INSERT INTO payment_webhook_events VALUES ('delivery-4', 'PAYMENT_STATUS_CHANGED', 'a')");
            assertThatThrownBy(() -> migrate(connection))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("웹훅 수신함에 중복 이벤트가 있습니다");

            assertThat(rowCount(connection)).isEqualTo(4);
            assertThat(hasEventConstraint(connection)).isFalse();
        }
    }

    private void legacyInbox(Connection connection) throws SQLException {
        execute(connection, """
            CREATE TABLE payment_webhook_events (
                transmission_id varchar(100) UNIQUE NOT NULL,
                event_type varchar(50) NOT NULL,
                event_hash varchar(128) NOT NULL
            );
            INSERT INTO payment_webhook_events VALUES
                ('delivery-1', 'PAYMENT_STATUS_CHANGED', 'a'),
                ('delivery-2', 'PAYMENT_STATUS_CHANGED', 'b'),
                ('delivery-3', 'DEPOSIT_CALLBACK', 'a');
            """);
    }

    private void migrate(Connection connection) throws Exception {
        try (var stream = getClass().getResourceAsStream("/db/manual/126_payment_webhook_inbox_deduplication.sql")) {
            try {
                execute(connection, new String(stream.readAllBytes(), StandardCharsets.UTF_8));
            } catch (SQLException exception) {
                execute(connection, "ROLLBACK");
                throw exception;
            }
        }
    }

    private boolean hasEventConstraint(Connection connection) throws SQLException {
        try (var statement = connection.createStatement(); var rows = statement.executeQuery("""
            SELECT pg_get_constraintdef(oid)
            FROM pg_constraint
            WHERE conrelid = 'payment_webhook_events'::regclass
              AND conname = 'uk_payment_webhook_events_event_type_hash'
            """)) {
            return rows.next() && "UNIQUE (event_type, event_hash)".equals(rows.getString(1));
        }
    }

    private int rowCount(Connection connection) throws SQLException {
        try (var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT count(*) FROM payment_webhook_events")) {
            rows.next();
            return rows.getInt(1);
        }
    }

    private void execute(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
