// 팔로우 PostgreSQL 검증을 매번 독립 스키마에서 실행하고 정리한다.
package com.kitschcatch.backend.domain.follow;

import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.UUID;

final class PostgresFollowTestDatabase implements AutoCloseable {
    final String url = System.getenv("ISSUE52_TEST_DB_URL");
    final String username = System.getenv().getOrDefault("ISSUE52_TEST_DB_USERNAME", "postgres");
    final String password = System.getenv().getOrDefault("ISSUE52_TEST_DB_PASSWORD", "");
    final String schema = "issue52_" + UUID.randomUUID().toString().replace("-", "");

    PostgresFollowTestDatabase() throws SQLException {
        try (var connection = DriverManager.getConnection(url, username, password); var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + schema);
        }
    }
    Connection connect() throws SQLException {
        var connection = DriverManager.getConnection(url, username, password);
        connection.setSchema(schema);
        return connection;
    }
    static void migrate(Connection connection) throws Exception {
        try (var stream = PostgresFollowTestDatabase.class.getResourceAsStream("/db/manual/052_user_follows.sql");
             var statement = connection.createStatement()) {
            if (stream == null) throw new IllegalStateException("팔로우 수동 SQL이 없습니다.");
            try { statement.execute(new String(stream.readAllBytes(), StandardCharsets.UTF_8)); }
            catch (SQLException exception) { statement.execute("ROLLBACK"); throw exception; }
        }
    }
    @Override
    public void close() throws SQLException {
        try (var connection = connect(); var statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA " + schema + " CASCADE");
        }
    }
}
