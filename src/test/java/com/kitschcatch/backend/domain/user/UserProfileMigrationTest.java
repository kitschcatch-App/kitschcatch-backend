// 실제 PostgreSQL에서 기존 사용자 보존과 수동 프로필 SQL의 제약 및 롤백을 검증한다.
package com.kitschcatch.backend.domain.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named = "ISSUE31_TEST_DB_URL", matches = "jdbc:postgresql:.*")
class UserProfileMigrationTest {

	@Test
	void migrationPreservesDuplicateLegacyNicknamesAndEnforcesProfileConstraints() throws Exception {
		try (var database = new PostgresProfileTestDatabase();
			 var connection = database.connect(); var statement = connection.createStatement()) {
			database.createLegacyUsers(connection);
			statement.execute("""
				INSERT INTO users (nickname, email, auth_provider, provider_user_id)
				VALUES ('카카오닉네임', 'first@example.com', 'KAKAO', 'first'),
				       ('카카오닉네임', 'second@example.com', 'KAKAO', 'second')
				""");

			PostgresProfileTestDatabase.migrate(connection);
			PostgresProfileTestDatabase.migrate(connection);

			try (var result = statement.executeQuery("""
				SELECT count(*) FROM users WHERE nickname = '카카오닉네임'
				AND nickname_key IS NULL AND profile_image_key IS NULL AND profile_registered_at IS NULL
				""")) {
				result.next();
				assertThat(result.getInt(1)).isEqualTo(2);
			}
			for (String invalidUpdate : new String[] {
				"UPDATE users SET nickname_key = 'invalid' WHERE id = 1",
				"UPDATE users SET profile_image_key = 'profiles/1/image.png' WHERE id = 1",
				"UPDATE users SET profile_registered_at = CURRENT_TIMESTAMP WHERE id = 1"
			}) {
				assertThatThrownBy(() -> statement.execute(invalidUpdate))
					.isInstanceOf(SQLException.class).extracting("SQLState").isEqualTo("23514");
			}
			statement.execute("UPDATE users SET nickname_key = 'collector', profile_registered_at = CURRENT_TIMESTAMP WHERE id = 1");
			assertThatThrownBy(() -> statement.execute(
				"UPDATE users SET nickname_key = 'collector', profile_registered_at = CURRENT_TIMESTAMP WHERE id = 2"))
				.isInstanceOf(SQLException.class).extracting("SQLState").isEqualTo("23505");
		}
	}

	@Test
	void sameNamedConstraintOnAnotherTableDoesNotSkipUsersConstraint() throws Exception {
		try (var database = new PostgresProfileTestDatabase();
			 var connection = database.connect(); var statement = connection.createStatement()) {
			database.createLegacyUsers(connection);
			statement.execute("CREATE TABLE unrelated (id BIGINT CONSTRAINT ck_users_profile_registration_fields CHECK (id > 0))");
			PostgresProfileTestDatabase.migrate(connection);

			assertThatThrownBy(() -> statement.execute("""
				INSERT INTO users (nickname, email, auth_provider, provider_user_id, nickname_key)
				VALUES ('invalid', 'invalid@example.com', 'KAKAO', 'invalid', 'invalid')
				"""))
				.isInstanceOf(SQLException.class).extracting("SQLState").isEqualTo("23514");
		}
	}

	@Test
	void failedMigrationRollsBackNewColumnsAndIndex() throws Exception {
		try (var database = new PostgresProfileTestDatabase();
			 var connection = database.connect(); var statement = connection.createStatement()) {
			database.createLegacyUsers(connection);
			statement.execute("ALTER TABLE users ADD COLUMN nickname_key VARCHAR(50)");
			statement.execute("""
				INSERT INTO users (nickname, email, auth_provider, provider_user_id, nickname_key)
				VALUES ('legacy', 'legacy@example.com', 'KAKAO', 'legacy', 'inconsistent')
				""");

			assertThatThrownBy(() -> PostgresProfileTestDatabase.migrate(connection))
				.isInstanceOf(SQLException.class).extracting("SQLState").isEqualTo("23514");
			try (var result = statement.executeQuery("""
				SELECT count(*) FROM information_schema.columns WHERE table_schema = current_schema()
				AND table_name = 'users' AND column_name IN ('profile_image_key', 'profile_registered_at')
				""")) {
				result.next();
				assertThat(result.getInt(1)).isZero();
			}
			try (var result = statement.executeQuery("SELECT to_regclass('uk_users_nickname_key')")) {
				result.next();
				assertThat(result.getString(1)).isNull();
			}
			try (var result = statement.executeQuery("SELECT nickname, nickname_key FROM users")) {
				result.next();
				assertThat(result.getString(1)).isEqualTo("legacy");
				assertThat(result.getString(2)).isEqualTo("inconsistent");
			}
		}
	}
}
