// 거래 후속 처리 검증마다 독립 PostgreSQL 스키마를 만들고 실제 수동 SQL을 적용한다.
package com.kitschcatch.backend.domain.order;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;

final class PostgresOrderLifecycleTestDatabase implements AutoCloseable {
    final String schema = "issue45_" + UUID.randomUUID().toString().replace("-", "");
    final String url = System.getenv("ISSUE45_TEST_DB_URL");
    final String username = System.getenv().getOrDefault("ISSUE45_TEST_DB_USERNAME", "postgres");
    final String password = System.getenv().getOrDefault("ISSUE45_TEST_DB_PASSWORD", "");

    PostgresOrderLifecycleTestDatabase() throws SQLException {
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

    static void replaceGeneratedColumns(Connection connection) throws SQLException, IOException {
        try (var stream = PostgresOrderLifecycleTestDatabase.class.getResourceAsStream("/db/manual/045_order_lifecycle.sql");
             var statement = connection.createStatement()) {
            String sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            var columns = java.util.regex.Pattern.compile("ADD COLUMN IF NOT EXISTS ([a-z_]+)").matcher(sql);
            while (columns.find()) statement.execute("ALTER TABLE orders DROP COLUMN IF EXISTS " + columns.group(1));
            migrate(connection);
            migrate(connection);
            statement.execute("DROP TABLE IF EXISTS settlement_recipients");
            for(String name:java.util.List.of("045_refund_review.sql","045_settlement_recipient.sql")) {
                try(var extra=PostgresOrderLifecycleTestDatabase.class.getResourceAsStream("/db/manual/"+name)) {
                    String extension=new String(extra.readAllBytes(),StandardCharsets.UTF_8);
                    var added=java.util.regex.Pattern.compile("ADD COLUMN IF NOT EXISTS ([a-z_]+)").matcher(extension);
                    while(added.find()) statement.execute("ALTER TABLE orders DROP COLUMN IF EXISTS "+added.group(1));
                    statement.execute(extension); statement.execute(extension);
                }
            }
        }
    }

    static void migrate(Connection connection) throws SQLException, IOException {
        try (var stream = PostgresOrderLifecycleTestDatabase.class.getResourceAsStream("/db/manual/045_order_lifecycle.sql");
             var statement = connection.createStatement()) {
            if (stream == null) throw new IOException("045_order_lifecycle.sql 파일이 없습니다.");
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
