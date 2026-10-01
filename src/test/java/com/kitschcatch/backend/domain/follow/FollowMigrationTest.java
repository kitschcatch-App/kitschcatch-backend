// PostgreSQL 수동 SQL의 재실행·롤백·제약·인덱스·삭제 정리를 검증한다.
package com.kitschcatch.backend.domain.follow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@EnabledIfEnvironmentVariable(named = "ISSUE52_TEST_DB_URL", matches = "jdbc:postgresql:.*")
class FollowMigrationTest {
    @Test
    void rerunPreservesRowsAndIndexesAndBothDirectionCascades() throws Exception {
        try (var db = new PostgresFollowTestDatabase(); var connection = db.connect(); var statement = connection.createStatement()) {
            prepare(connection);
            statement.execute("INSERT INTO user_follows(follower_id, following_id, created_at) VALUES (1,2,TIMESTAMP '2026-10-01 00:00:00'), (2,1,TIMESTAMP '2026-10-01 00:00:00')");
            PostgresFollowTestDatabase.migrate(connection);
            try (var rows = statement.executeQuery("SELECT follower_id,following_id,created_at FROM user_follows ORDER BY id")) {
                assertThat(rows.next()).isTrue(); assertThat(rows.getLong(1)).isEqualTo(1); assertThat(rows.getLong(2)).isEqualTo(2);
                assertThat(rows.getTimestamp(3).toLocalDateTime()).isEqualTo("2026-10-01T00:00:00");
                assertThat(rows.next()).isTrue(); assertThat(rows.getLong(1)).isEqualTo(2); assertThat(rows.getLong(2)).isEqualTo(1);
                assertThat(rows.next()).isFalse();
            }
            try (var indexes = statement.executeQuery("SELECT indexname,indexdef FROM pg_indexes WHERE schemaname=current_schema() AND tablename='user_follows'")) {
                var definitions = new java.util.HashMap<String,String>();
                while (indexes.next()) definitions.put(indexes.getString(1), indexes.getString(2));
                assertThat(definitions).hasSize(4);
                assertThat(definitions.get("ix_user_follows_follower_created_id")).contains("follower_id, created_at DESC, id DESC");
                assertThat(definitions.get("ix_user_follows_following_created_id")).contains("following_id, created_at DESC, id DESC");
            }
            statement.execute("DELETE FROM users WHERE id=1");
            try (var rows = statement.executeQuery("SELECT count(*) FROM user_follows")) { rows.next(); assertThat(rows.getLong(1)).isZero(); }
        }
    }
    @ParameterizedTest
    @CsvSource(delimiter='|', value={
        "INSERT INTO user_follows(follower_id,following_id) VALUES(1,2) | 23505",
        "INSERT INTO user_follows(follower_id,following_id) VALUES(1,1) | 23514",
        "INSERT INTO user_follows(follower_id,following_id) VALUES(99,2) | 23503",
        "INSERT INTO user_follows(follower_id,following_id) VALUES(1,99) | 23503",
        "INSERT INTO user_follows(follower_id,following_id) VALUES(null,2) | 23502",
        "INSERT INTO user_follows(follower_id,following_id) VALUES(1,null) | 23502",
        "INSERT INTO user_follows(follower_id,following_id,created_at) VALUES(2,1,null) | 23502"
    })
    void databaseRejectsInvalidRelations(String sql, String state) throws Exception {
        try (var db = new PostgresFollowTestDatabase(); var connection = db.connect(); var statement = connection.createStatement()) {
            prepare(connection); statement.execute("INSERT INTO user_follows(follower_id,following_id) VALUES(1,2)");
            assertThatThrownBy(() -> statement.execute(sql)).isInstanceOf(SQLException.class)
                .extracting(error -> ((SQLException) error).getSQLState()).isEqualTo(state);
        }
    }
    @Test
    void missingUsersRollsBackWholeMigration() throws Exception {
        try (var db = new PostgresFollowTestDatabase(); var connection = db.connect(); var statement = connection.createStatement()) {
            assertThatThrownBy(() -> PostgresFollowTestDatabase.migrate(connection)).isInstanceOf(SQLException.class);
            try (var rows = statement.executeQuery("SELECT to_regclass('user_follows')")) { rows.next(); assertThat(rows.getString(1)).isNull(); }
            prepare(connection);
        }
    }
    private void prepare(Connection connection) throws Exception {
        try (var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE users(id BIGINT PRIMARY KEY)"); statement.execute("INSERT INTO users VALUES(1),(2)");
        }
        PostgresFollowTestDatabase.migrate(connection);
    }
}
