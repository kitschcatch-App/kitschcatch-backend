// PostgreSQL 검증마다 독립 스키마를 생성하고 실제 상품 수동 SQL을 적용한다.
package com.kitschcatch.backend.domain.post;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;

final class PostgresPostFavoriteTestDatabase implements AutoCloseable {
    final String schema;
    final String url;
    final String username;
    final String password;

    PostgresPostFavoriteTestDatabase(String issue) throws SQLException {
        schema = "issue" + issue + "_" + UUID.randomUUID().toString().replace("-", "");
        url = System.getenv("ISSUE" + issue + "_TEST_DB_URL");
        username = System.getenv().getOrDefault("ISSUE" + issue + "_TEST_DB_USERNAME", "postgres");
        password = System.getenv().getOrDefault("ISSUE" + issue + "_TEST_DB_PASSWORD", "");
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

    static void migrateFavorites(Connection connection) throws SQLException, IOException {
        migrate(connection, "051_post_favorites.sql");
    }

    private static void migrate(Connection connection, String file) throws SQLException, IOException {
        try (var stream = PostgresPostFavoriteTestDatabase.class.getResourceAsStream("/db/manual/" + file);
             var statement = connection.createStatement()) {
            if (stream == null) throw new IOException(file + " 파일이 없습니다.");
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
