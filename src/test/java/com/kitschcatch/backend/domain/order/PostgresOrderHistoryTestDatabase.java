// 거래 내역 검증마다 독립 PostgreSQL 스키마를 만들고 실제 수동 SQL을 적용한다.
package com.kitschcatch.backend.domain.order;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;

final class PostgresOrderHistoryTestDatabase implements AutoCloseable {
    final String schema = "issue43_" + UUID.randomUUID().toString().replace("-", "");
    final String url = System.getenv("ISSUE43_TEST_DB_URL");
    final String username = System.getenv().getOrDefault("ISSUE43_TEST_DB_USERNAME", "postgres");
    final String password = System.getenv().getOrDefault("ISSUE43_TEST_DB_PASSWORD", "");

    PostgresOrderHistoryTestDatabase() throws SQLException {
        try (var connection = DriverManager.getConnection(url, username, password);
             var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + schema);
        }
    }

    Connection connect() throws SQLException {
        var connection = DriverManager.getConnection(url, username, password);
        connection.setSchema(schema);
        return connection;
    }

    static void migrate(Connection connection) throws SQLException, IOException {
        try (var stream = PostgresOrderHistoryTestDatabase.class.getResourceAsStream("/db/manual/043_order_history.sql");
             var statement = connection.createStatement()) {
            if (stream == null) throw new IOException("043_order_history.sql 파일이 없습니다.");
            try {
                statement.execute(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
            } catch (SQLException exception) {
                statement.execute("ROLLBACK");
                throw exception;
            }
        }
    }

    @Override
    public void close() throws SQLException {
        try (var connection = connect(); var statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA " + schema + " CASCADE");
        }
    }
}
