// PostgreSQL 수동 SQL의 재실행·참조·유일·필수값 제약과 삭제 정리 및 인덱스를 검증한다.
package com.kitschcatch.backend.domain.post;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@EnabledIfEnvironmentVariable(named = "ISSUE51_TEST_DB_URL", matches = "jdbc:postgresql:.*")
class PostFavoriteMigrationTest {
    @Test
    void rerunPreservesRowsAndIndexesAndDeleteCascadesFromEitherParent() throws Exception {
        try (var database = new PostgresPostFavoriteTestDatabase("51"); var connection = database.connect();
             var statement = connection.createStatement()) {
            prepare(connection);
            statement.execute("INSERT INTO post_favorites(user_id, post_id, created_at) VALUES (1, 1, TIMESTAMP '2026-09-30 10:00:00')");
            PostgresPostFavoriteTestDatabase.migrateFavorites(connection);
            try (var result = statement.executeQuery("SELECT user_id, post_id, created_at FROM post_favorites")) {
                assertThat(result.next()).isTrue();
                assertThat(result.getLong(1)).isEqualTo(1L);
                assertThat(result.getLong(2)).isEqualTo(1L);
                assertThat(result.getTimestamp(3).toLocalDateTime()).isEqualTo("2026-09-30T10:00:00");
                assertThat(result.next()).isFalse();
            }
            try (var result = statement.executeQuery("SELECT count(*) FROM pg_indexes WHERE schemaname=current_schema() AND tablename='post_favorites'")) {
                result.next(); assertThat(result.getInt(1)).isEqualTo(4);
            }
            statement.execute("DELETE FROM users WHERE id=1");
            assertEmpty(connection);
            statement.execute("INSERT INTO post_favorites(user_id, post_id) VALUES (2, 1)");
            statement.execute("DELETE FROM posts WHERE id=1");
            assertEmpty(connection);
        }
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "INSERT INTO post_favorites(user_id, post_id) VALUES (1, 1) | 23505",
        "INSERT INTO post_favorites(user_id, post_id) VALUES (99, 1) | 23503",
        "INSERT INTO post_favorites(user_id, post_id) VALUES (1, 99) | 23503",
        "INSERT INTO post_favorites(user_id, post_id) VALUES (null, 1) | 23502",
        "INSERT INTO post_favorites(user_id, post_id) VALUES (1, null) | 23502",
        "INSERT INTO post_favorites(user_id, post_id, created_at) VALUES (2, 1, null) | 23502"
    })
    void constraintsRejectInvalidRelations(String sql, String sqlState) throws Exception {
        try (var database = new PostgresPostFavoriteTestDatabase("51"); var connection = database.connect();
             var statement = connection.createStatement()) {
            prepare(connection);
            statement.execute("INSERT INTO post_favorites(user_id, post_id) VALUES (1, 1)");
            assertThatThrownBy(() -> statement.execute(sql)).isInstanceOf(SQLException.class)
                .extracting(error -> ((SQLException) error).getSQLState()).isEqualTo(sqlState);
        }
    }

    private void prepare(Connection connection) throws Exception {
        try (var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE users(id BIGINT PRIMARY KEY)");
            statement.execute("INSERT INTO users VALUES (1), (2)");
            statement.execute("CREATE TABLE posts(id BIGINT PRIMARY KEY)");
            statement.execute("INSERT INTO posts VALUES (1)");
            PostgresPostFavoriteTestDatabase.migrateFavorites(connection);
        }
    }

    private void assertEmpty(Connection connection) throws Exception {
        try (var statement = connection.createStatement(); var result = statement.executeQuery("SELECT count(*) FROM post_favorites")) {
            result.next(); assertThat(result.getInt(1)).isZero();
        }
    }
}
