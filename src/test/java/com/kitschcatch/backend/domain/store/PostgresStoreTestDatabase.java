// PostgreSQL 검증마다 독립 스키마를 생성하고 실제 매장 수동 SQL을 적용한다.
package com.kitschcatch.backend.domain.store;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;

final class PostgresStoreTestDatabase implements AutoCloseable {
    final String schema = "issue39_" + UUID.randomUUID().toString().replace("-", "");
    final String url = System.getenv("ISSUE39_TEST_DB_URL");
    final String username = System.getenv().getOrDefault("ISSUE39_TEST_DB_USERNAME", "postgres");
    final String password = System.getenv().getOrDefault("ISSUE39_TEST_DB_PASSWORD", "");

    PostgresStoreTestDatabase() throws SQLException {
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
        try (var stream = PostgresStoreTestDatabase.class.getResourceAsStream("/db/manual/039_stores.sql");
             var statement = connection.createStatement()) {
            if (stream == null) throw new IOException("039_stores.sql 파일이 없습니다.");
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
