// 매장 수동 SQL의 재실행 안전성과 좌표·영업시간·참조 제약을 PostgreSQL에서 검증한다.
package com.kitschcatch.backend.domain.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@EnabledIfEnvironmentVariable(named = "ISSUE39_TEST_DB_URL", matches = "jdbc:postgresql:.*")
class StoreMigrationTest {
    private static final String STORE = """
        INSERT INTO stores(name, region, address, latitude, longitude)
        VALUES ('검증 매장', '서울', '검증 주소', 0, 0) RETURNING id
        """;

    @Test
    void rerunPreservesRowsAndDeleteCascadesHours() throws Exception {
        try (var database = new PostgresStoreTestDatabase(); var connection = database.connect();
             var statement = connection.createStatement()) {
            PostgresStoreTestDatabase.migrate(connection);
            long id;
            try (var result = statement.executeQuery(STORE)) { result.next(); id = result.getLong(1); }
            statement.execute("INSERT INTO store_business_hours VALUES (" + id + ", 'MONDAY', '20:00', '02:00', false)");
            PostgresStoreTestDatabase.migrate(connection);
            try (var result = statement.executeQuery("SELECT count(*) FROM store_business_hours")) {
                result.next(); assertThat(result.getInt(1)).isEqualTo(1);
            }
            assertThatThrownBy(() -> statement.execute("INSERT INTO store_business_hours VALUES (" + id + ", 'MONDAY', null, null, true)"))
                .isInstanceOf(SQLException.class).extracting(e -> ((SQLException) e).getSQLState()).isEqualTo("23505");
            statement.execute("DELETE FROM stores WHERE id = " + id);
            try (var result = statement.executeQuery("SELECT count(*) FROM store_business_hours")) {
                result.next(); assertThat(result.getInt(1)).isZero();
            }
            assertThatThrownBy(() -> statement.execute("INSERT INTO store_business_hours VALUES (" + id + ", 'MONDAY', null, null, true)"))
                .isInstanceOf(SQLException.class).extracting(e -> ((SQLException) e).getSQLState()).isEqualTo("23503");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"latitude=91", "latitude=-91", "latitude='NaN'", "latitude='Infinity'",
        "longitude=181", "longitude=-181", "longitude='NaN'", "longitude='-Infinity'",
        "region='미등록지역'", "name=' '", "address=''"})
    void rejectsInvalidStoreData(String assignment) throws Exception {
        try (var database = new PostgresStoreTestDatabase(); var connection = database.connect();
             var statement = connection.createStatement()) {
            PostgresStoreTestDatabase.migrate(connection);
            statement.execute(STORE);
            assertThatThrownBy(() -> statement.execute("UPDATE stores SET " + assignment))
                .isInstanceOf(SQLException.class).extracting(e -> ((SQLException) e).getSQLState()).isEqualTo("23514");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"'MONDAY', null, null, false", "'MONDAY', '11:00', null, false",
        "'MONDAY', '11:00', '20:00', true", "'UNKNOWN', null, null, true",
        "'MONDAY', '11:00:01', '20:00', false", "'MONDAY', '11:00', '24:00', false"})
    void rejectsInvalidHours(String values) throws Exception {
        try (var database = new PostgresStoreTestDatabase(); var connection = database.connect();
             var statement = connection.createStatement()) {
            PostgresStoreTestDatabase.migrate(connection);
            long id;
            try (var result = statement.executeQuery(STORE)) { result.next(); id = result.getLong(1); }
            assertThatThrownBy(() -> statement.execute("INSERT INTO store_business_hours VALUES (" + id + ", " + values + ")"))
                .isInstanceOf(SQLException.class).extracting(e -> ((SQLException) e).getSQLState()).isEqualTo("23514");
        }
    }
}
