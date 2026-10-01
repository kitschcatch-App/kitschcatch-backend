// 이슈 54 검증용 PostgreSQL 스키마를 생성하고 종료 시 해당 스키마만 삭제한다.
package com.kitschcatch.backend.domain.order.service;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;

final class Issue54PostgresDatabase implements AutoCloseable {
    final String schema = "issue54_" + UUID.randomUUID().toString().replace("-", "");
    final String url = System.getenv("ISSUE54_TEST_DB_URL");
    final String user = System.getenv().getOrDefault("ISSUE54_TEST_DB_USER", "issue54");
    final String password = System.getenv().getOrDefault("ISSUE54_TEST_DB_PASSWORD", "");

    Issue54PostgresDatabase() throws SQLException {
        try (var connection = DriverManager.getConnection(url, user, password);
             var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + schema);
        }
    }

    Connection connect() throws SQLException {
        var connection = DriverManager.getConnection(url, user, password);
        connection.setSchema(schema);
        return connection;
    }

    String scopedUrl() {
        return url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema;
    }

    @Override
    public void close() throws SQLException {
        try (var connection = connect(); var statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA " + schema + " CASCADE");
        }
    }
}
