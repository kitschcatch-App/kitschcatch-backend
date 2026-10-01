// 공개 프로필 검증용 PostgreSQL 스키마를 격리하고 수동 SQL을 적용한다.
package com.kitschcatch.backend.domain.user;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;

final class PublicProfilePostgresDatabase implements AutoCloseable {
	final String schema = "issue49_" + UUID.randomUUID().toString().replace("-", "");
	final String url = System.getenv("ISSUE49_TEST_DB_URL");
	final String username = System.getenv().getOrDefault("ISSUE49_TEST_DB_USERNAME", "postgres");
	final String password = System.getenv().getOrDefault("ISSUE49_TEST_DB_PASSWORD", "");

	PublicProfilePostgresDatabase() throws SQLException {
		try (var connection = DriverManager.getConnection(url, username, password); var statement = connection.createStatement()) {
			statement.execute("CREATE SCHEMA " + schema);
		}
	}

	Connection connect() throws SQLException {
		var connection = DriverManager.getConnection(url, username, password);
		connection.setSchema(schema);
		return connection;
	}

	static void migrate(Connection connection, String file) throws SQLException, IOException {
		try (var stream = PublicProfilePostgresDatabase.class.getResourceAsStream("/db/manual/" + file);
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
