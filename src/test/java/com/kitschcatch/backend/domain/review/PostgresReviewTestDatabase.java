// 후기 검증마다 전용 PostgreSQL 스키마를 만들고 수동 SQL을 적용한다.
package com.kitschcatch.backend.domain.review;

import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.UUID;

final class PostgresReviewTestDatabase implements AutoCloseable {
    final String url = System.getenv("ISSUE56_TEST_DB_URL");
    final String username = System.getenv().getOrDefault("ISSUE56_TEST_DB_USERNAME", "postgres");
    final String password = System.getenv().getOrDefault("ISSUE56_TEST_DB_PASSWORD", "");
    final String schema = "issue56_" + UUID.randomUUID().toString().replace("-", "");

    PostgresReviewTestDatabase() throws SQLException {
        try (var c = DriverManager.getConnection(url, username, password); var s = c.createStatement()) {
            s.execute("CREATE SCHEMA " + schema);
        }
    }
    Connection connect() throws SQLException {
        var c = DriverManager.getConnection(url, username, password);
        c.setSchema(schema);
        return c;
    }
    static void migrate(Connection c) throws Exception {
        try (var input = PostgresReviewTestDatabase.class.getResourceAsStream("/db/manual/056_transaction_reviews.sql");
             var s = c.createStatement()) {
            try { s.execute(new String(input.readAllBytes(), StandardCharsets.UTF_8)); }
            catch (SQLException e) { s.execute("ROLLBACK"); throw e; }
        }
    }
    @Override public void close() throws SQLException {
        try (var c = connect(); var s = c.createStatement()) { s.execute("DROP SCHEMA " + schema + " CASCADE"); }
    }
}
