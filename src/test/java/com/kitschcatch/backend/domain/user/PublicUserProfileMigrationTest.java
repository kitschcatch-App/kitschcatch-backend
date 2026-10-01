// PostgreSQL에서 선택적 프로필 SQL 재실행과 기존 데이터 보존 및 제약 실패 롤백을 검증한다.
package com.kitschcatch.backend.domain.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named = "ISSUE49_TEST_DB_URL", matches = "jdbc:postgresql:.*")
class PublicUserProfileMigrationTest {
	@Test
	void repeatableMigrationPreservesLegacyUsersAndEnforcesUniqueAndCanonicalFields() throws Exception {
		try (var database = new PublicProfilePostgresDatabase(); var connection = database.connect();
			var statement = connection.createStatement()) {
			createLegacy(connection);
			statement.execute("CREATE TABLE unrelated (id BIGINT CONSTRAINT ck_users_public_profile_fields CHECK (id > 0))");
			PublicProfilePostgresDatabase.migrate(connection, "049_public_user_profile.sql");
			PublicProfilePostgresDatabase.migrate(connection, "049_public_user_profile.sql");
			try (var result = statement.executeQuery("SELECT count(*) FROM users WHERE username IS NULL AND bio IS NULL")) {
				result.next();
				assertThat(result.getInt(1)).isEqualTo(3);
			}
			try (var result = statement.executeQuery("SELECT nickname, profile_image_key, profile_registered_at FROM users WHERE id=1")) {
				result.next();
				assertThat(result.getString(1)).isEqualTo("registered");
				assertThat(result.getString(2)).isEqualTo("profiles/1/image.png");
				assertThat(result.getTimestamp(3).toInstant()).isEqualTo(java.time.Instant.parse("2026-09-01T00:00:00Z"));
			}
			for (String invalid : new String[] {"username='UPPER'", "username='ab'", "username='bad-name'", "bio=''", "bio=E'a\\nb'"}) {
				assertThatThrownBy(() -> statement.execute("UPDATE users SET " + invalid + " WHERE id=1"))
					.isInstanceOf(SQLException.class).extracting("SQLState").isEqualTo("23514");
			}
			assertThatThrownBy(() -> statement.execute("UPDATE users SET username='valid' WHERE id=3"))
				.isInstanceOf(SQLException.class).extracting("SQLState").isEqualTo("23514");
			statement.execute("UPDATE users SET username='valid', bio='intro' WHERE id=1");
			assertThatThrownBy(() -> statement.execute("UPDATE users SET username='valid' WHERE id=2"))
				.isInstanceOf(SQLException.class).extracting("SQLState").isEqualTo("23505");
		}
	}

	@Test
	void duplicateExistingUsernameRollsBackBioColumnAndIndex() throws Exception {
		try (var database = new PublicProfilePostgresDatabase(); var connection = database.connect();
			var statement = connection.createStatement()) {
			createLegacy(connection);
			statement.execute("ALTER TABLE users ADD COLUMN username VARCHAR(30)");
			statement.execute("UPDATE users SET username='duplicate' WHERE id IN (1,2)");
			assertThatThrownBy(() -> PublicProfilePostgresDatabase.migrate(connection, "049_public_user_profile.sql"))
				.isInstanceOf(SQLException.class).extracting("SQLState").isEqualTo("23505");
			assertRolledBack(connection);
		}
	}

	@Test
	void invalidExistingUsernameRollsBackAllNewDdlWithoutRewritingData() throws Exception {
		try (var database = new PublicProfilePostgresDatabase(); var connection = database.connect();
			var statement = connection.createStatement()) {
			createLegacy(connection);
			statement.execute("ALTER TABLE users ADD COLUMN username VARCHAR(30)");
			statement.execute("UPDATE users SET username='INVALID' WHERE id=1");
			assertThatThrownBy(() -> PublicProfilePostgresDatabase.migrate(connection, "049_public_user_profile.sql"))
				.isInstanceOf(SQLException.class).extracting("SQLState").isEqualTo("23514");
			assertRolledBack(connection);
			try (var result = statement.executeQuery("SELECT username FROM users WHERE id=1")) {
				result.next();
				assertThat(result.getString(1)).isEqualTo("INVALID");
			}
		}
	}

	private void assertRolledBack(Connection connection) throws SQLException {
		try (var statement = connection.createStatement(); var result = statement.executeQuery("""
			SELECT count(*) FROM information_schema.columns WHERE table_schema=current_schema()
			AND table_name='users' AND column_name='bio'
			""")) {
			result.next();
			assertThat(result.getInt(1)).isZero();
		}
		try (var statement = connection.createStatement(); var result = statement.executeQuery("SELECT to_regclass('uk_users_username')")) {
			result.next();
			assertThat(result.getString(1)).isNull();
		}
	}

	private void createLegacy(Connection connection) throws Exception {
		try (var statement = connection.createStatement()) {
			statement.execute("""
				CREATE TABLE users (id BIGINT PRIMARY KEY, nickname VARCHAR(50), nickname_key VARCHAR(50),
				    profile_image_key VARCHAR(512), profile_registered_at TIMESTAMPTZ);
				INSERT INTO users VALUES (1, 'registered', 'registered', 'profiles/1/image.png', '2026-09-01T00:00:00Z'),
				    (2, 'registered-other', 'registered-other', NULL, '2026-09-01T00:00:00Z'),
				    (3, 'provider-default', NULL, NULL, NULL);
				""");
		}
	}
}
