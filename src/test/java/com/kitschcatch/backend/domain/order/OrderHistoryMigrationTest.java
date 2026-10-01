// 기존 주문의 스냅샷 보완·재실행 보존·인덱스와 실패 시 원자적 롤백을 PostgreSQL에서 검증한다.
package com.kitschcatch.backend.domain.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named = "ISSUE43_TEST_DB_URL", matches = "jdbc:postgresql:.*")
class OrderHistoryMigrationTest {
    @Test
    void backfillsFromCurrentDataAndPreservesSnapshotsOnRerun() throws Exception {
        try (var db = new PostgresOrderHistoryTestDatabase(); var connection = db.connect();
             var statement = connection.createStatement()) {
            prepareLegacy(connection);
            statement.execute("INSERT INTO post_images VALUES (3, 10, 'later.png', 1), (2, 10, 'second.png', 0), (1, 10, 'first.png', 0)");
            PostgresOrderHistoryTestDatabase.migrate(connection);
            assertSnapshot(connection, 1, "현재 구매자", "first.png");
            statement.execute("UPDATE users SET nickname='새 닉네임'");
            statement.execute("UPDATE post_images SET object_key='changed.png'");
            PostgresOrderHistoryTestDatabase.migrate(connection);
            assertSnapshot(connection, 1, "현재 구매자", "first.png");
            try (var result = statement.executeQuery("SELECT post_title, seller_nickname, amount, order_status FROM orders WHERE id=1")) {
                result.next();
                assertThat(result.getString(1)).isEqualTo("옛 상품명");
                assertThat(result.getString(2)).isEqualTo("옛 판매자");
                assertThat(result.getLong(3)).isEqualTo(12000L);
                assertThat(result.getString(4)).isEqualTo("PAID");
            }
        }
    }

    @Test
    void absentImageStaysAbsentEvenIfImageIsAddedBeforeRerun() throws Exception {
        try (var db = new PostgresOrderHistoryTestDatabase(); var connection = db.connect();
             var statement = connection.createStatement()) {
            prepareLegacy(connection);
            PostgresOrderHistoryTestDatabase.migrate(connection);
            assertSnapshot(connection, 1, "현재 구매자", null);
            statement.execute("INSERT INTO post_images VALUES (1, 10, 'new.png', 0)");
            PostgresOrderHistoryTestDatabase.migrate(connection);
            assertSnapshot(connection, 1, "현재 구매자", null);
        }
    }

    @Test
    void preservesAlreadyCapturedNewOrderValuesAndValidatesColumnDefinitions() throws Exception {
        try (var db = new PostgresOrderHistoryTestDatabase(); var connection = db.connect();
             var statement = connection.createStatement()) {
            prepareLegacy(connection);
            PostgresOrderHistoryTestDatabase.migrate(connection);
            statement.execute("UPDATE orders SET buyer_nickname='주문 당시 구매자', post_thumbnail_key='snapshot.png'");
            PostgresOrderHistoryTestDatabase.migrate(connection);
            assertSnapshot(connection, 1, "주문 당시 구매자", "snapshot.png");
            try (var result = statement.executeQuery("SELECT character_maximum_length, is_nullable FROM information_schema.columns WHERE table_schema=current_schema() AND table_name='orders' AND column_name='buyer_nickname'")) {
                result.next(); assertThat(result.getInt(1)).isEqualTo(50); assertThat(result.getString(2)).isEqualTo("NO");
            }
            try (var result = statement.executeQuery("SELECT character_maximum_length, is_nullable FROM information_schema.columns WHERE table_schema=current_schema() AND table_name='orders' AND column_name='post_thumbnail_key'")) {
                result.next(); assertThat(result.getInt(1)).isEqualTo(512); assertThat(result.getString(2)).isEqualTo("YES");
            }
            assertThatThrownBy(() -> statement.execute("UPDATE orders SET buyer_nickname=NULL"))
                .isInstanceOf(SQLException.class).extracting(error -> ((SQLException) error).getSQLState()).isEqualTo("23502");
        }
    }

    @Test
    void createsExactlyFourHistoryIndexesWithExpectedSortAndFilterColumns() throws Exception {
        try (var db = new PostgresOrderHistoryTestDatabase(); var connection = db.connect();
             var statement = connection.createStatement()) {
            prepareLegacy(connection);
            PostgresOrderHistoryTestDatabase.migrate(connection);
            PostgresOrderHistoryTestDatabase.migrate(connection);
            var definitions = new java.util.ArrayList<String>();
            try (var result = statement.executeQuery("SELECT indexdef FROM pg_indexes WHERE schemaname=current_schema() AND indexname LIKE 'ix_orders_%'")) {
                while (result.next()) definitions.add(result.getString(1));
            }
            assertThat(definitions).hasSize(4);
            for (String columns : java.util.List.of("(user_id, created_at DESC, id DESC)", "(user_id, order_status, created_at DESC, id DESC)",
                "(seller_id, created_at DESC, id DESC)", "(seller_id, order_status, created_at DESC, id DESC)")) {
                assertThat(definitions).anySatisfy(definition -> assertThat(definition).contains(columns));
            }
        }
    }

    @Test
    void inconsistentLegacyUserFailsAndRollsBackAllAddedColumns() throws Exception {
        try (var db = new PostgresOrderHistoryTestDatabase(); var connection = db.connect();
             var statement = connection.createStatement()) {
            prepareLegacy(connection);
            statement.execute("UPDATE orders SET user_id=99");
            assertThatThrownBy(() -> PostgresOrderHistoryTestDatabase.migrate(connection))
                .isInstanceOf(SQLException.class).extracting(error -> ((SQLException) error).getSQLState()).isEqualTo("23502");
            try (var result = statement.executeQuery("SELECT count(*) FROM information_schema.columns WHERE table_schema=current_schema() AND table_name='orders' AND column_name IN ('buyer_nickname', 'post_thumbnail_key')")) {
                result.next(); assertThat(result.getInt(1)).isZero();
            }
            try (var result = statement.executeQuery("SELECT amount FROM orders WHERE id=1")) {
                result.next(); assertThat(result.getLong(1)).isEqualTo(12000L);
            }
        }
    }

    private void prepareLegacy(Connection connection) throws Exception {
        try (var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE users(id BIGINT PRIMARY KEY, nickname VARCHAR(50) NOT NULL)");
            statement.execute("CREATE TABLE post_images(id BIGINT PRIMARY KEY, post_id BIGINT NOT NULL, object_key VARCHAR(512) NOT NULL, sort_order INTEGER NOT NULL)");
            statement.execute("CREATE TABLE orders(id BIGINT PRIMARY KEY, user_id BIGINT NOT NULL, post_id BIGINT NOT NULL, seller_id BIGINT NOT NULL, post_title VARCHAR(100) NOT NULL, seller_nickname VARCHAR(50) NOT NULL, amount BIGINT NOT NULL, order_status VARCHAR(30) NOT NULL, created_at TIMESTAMP NOT NULL)");
            statement.execute("INSERT INTO users VALUES (1, '현재 구매자')");
            statement.execute("INSERT INTO orders VALUES (1, 1, 10, 2, '옛 상품명', '옛 판매자', 12000, 'PAID', TIMESTAMP '2026-01-01 00:00:00')");
        }
    }

    private void assertSnapshot(Connection connection, long id, String buyer, String image) throws Exception {
        try (var statement = connection.prepareStatement("SELECT buyer_nickname, post_thumbnail_key FROM orders WHERE id=?")) {
            statement.setLong(1, id);
            try (var result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                assertThat(result.getString(1)).isEqualTo(buyer);
                assertThat(result.getString(2)).isEqualTo(image);
            }
        }
    }
}
