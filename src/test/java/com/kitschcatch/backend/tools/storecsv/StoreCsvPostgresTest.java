// 격리 PostgreSQL에서 재실행·관심 관계·권한·동시성·sequence 및 실패 원자성을 검증한다.
package com.kitschcatch.backend.tools.storecsv;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

@EnabledIfEnvironmentVariable(named = "ISSUE55_TEST_DB_URL", matches = "jdbc:postgresql:.*")
class StoreCsvPostgresTest {
    @TempDir Path directory;
    String schema;
    final StoreCsvImporter importer = new StoreCsvImporter();

    Connection connect() throws SQLException {
        var connection = DriverManager.getConnection(System.getenv("ISSUE55_TEST_DB_URL"),
            System.getenv().getOrDefault("ISSUE55_TEST_DB_USERNAME", "postgres"),
            System.getenv().getOrDefault("ISSUE55_TEST_DB_PASSWORD", ""));
        if (schema != null) connection.setSchema(schema);
        return connection;
    }

    @BeforeEach
    void setup() throws Exception {
        schema = "issue55_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = connect(); var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + schema);
            connection.setSchema(schema);
            migrate(connection, "039_stores.sql");
            statement.execute("CREATE TABLE users(id BIGINT PRIMARY KEY)");
            migrate(connection, "041_store_favorites.sql");
            migrate(connection, "055_store_csv_imports.sql");
            migrate(connection, "055_store_csv_imports.sql");
        }
    }

    @AfterEach
    void cleanup() throws Exception {
        if (schema == null) return;
        try (var connection = connect(); var statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA " + schema + " CASCADE");
        }
    }

    private void migrate(Connection connection, String name) throws Exception {
        try (var stream = getClass().getResourceAsStream("/db/manual/" + name);
             var statement = connection.createStatement()) {
            statement.execute(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    StoreCsvBatch fixture() throws Exception {
        return StoreCsvBatch.load(Path.of("src/test/resources/issue55/stores.csv"),
            Path.of("src/test/resources/issue55/business-hours.csv"), StoreCsvBatchTest.REVIEW);
    }

    StoreCsvBatch batch(String stores, String hours) throws Exception {
        return StoreCsvBatch.load(Files.writeString(directory.resolve("stores.csv"), StoreCsvBatchTest.HEADER + stores),
            Files.writeString(directory.resolve("hours.csv"), StoreCsvBatchTest.HOURS + hours), StoreCsvBatchTest.REVIEW);
    }

    void sql(String sql) throws Exception {
        try (var connection = connect(); var statement = connection.createStatement()) { statement.execute(sql); }
    }

    String scalar(String sql) throws Exception {
        try (var connection = connect(); var statement = connection.createStatement(); var rows = statement.executeQuery(sql)) {
            rows.next(); return rows.getString(1);
        }
    }

    StoreCsvImporter.Result run(StoreCsvBatch batch, boolean apply) throws Exception {
        try (var connection = connect()) { return importer.execute(connection, schema, batch, apply); }
    }

    @Test
    void dryRunWritesNothingIncludingSequenceAndAudit() throws Exception {
        var result = run(fixture(), false);
        assertThat(result.newStores()).isEqualTo(2);
        assertThat(result.applied()).isFalse();
        assertThat(scalar("SELECT count(*) FROM stores")).isEqualTo("0");
        assertThat(scalar("SELECT count(*) FROM store_csv_imports")).isEqualTo("0");
        assertThat(scalar("SELECT last_value||':'||is_called FROM stores_id_seq")).isEqualTo("1:false");
    }

    @Test
    void rerunAndUpdatesPreserveFavoritesMissingStoresAndMissingDays() throws Exception {
        run(fixture(), true);
        sql("INSERT INTO users VALUES(1); INSERT INTO store_favorites(user_id,store_id) VALUES(1,55001)");
        String favorite = scalar("SELECT id||':'||created_at FROM store_favorites");
        var rerun = run(fixture(), true);
        assertThat(rerun.newStores()).isZero();
        assertThat(rerun.existingStores()).isEqualTo(2);
        run(batch("55001,갱신 합성점,서울,갱신 주소,0,0,123\n", "55001,MONDAY,11:00,20:00,false\n"), true);
        assertThat(scalar("SELECT name FROM stores WHERE id=55001")).isEqualTo("갱신 합성점");
        assertThat(scalar("SELECT count(*) FROM stores")).isEqualTo("2");
        assertThat(scalar("SELECT count(*) FROM store_business_hours")).isEqualTo("3");
        assertThat(scalar("SELECT id||':'||created_at FROM store_favorites")).isEqualTo(favorite);
        assertThat(scalar("SELECT count(*) FROM store_csv_imports")).isEqualTo("3");
        assertThat(scalar("SELECT source||':'||reviewer||':'||array_length(store_ids,1) FROM store_csv_imports LIMIT 1"))
            .isEqualTo("합성 fixture:검증자:2");
        assertThat(scalar("SELECT nextval('stores_id_seq')")).isEqualTo("55003");
    }

    @Test
    void hoursOnlyCanUseExistingParentAndNoParentFailsBothModes() throws Exception {
        var missing = batch("", "123,MONDAY,,,true\n");
        assertThatThrownBy(() -> run(missing, false)).hasMessageContaining("부모");
        assertThatThrownBy(() -> run(missing, true)).hasMessageContaining("부모");
        run(fixture(), true);
        run(batch("", "55002,SUNDAY,,,true\n"), true);
        assertThat(scalar("SELECT count(*) FROM store_business_hours")).isEqualTo("4");
    }

    @Test
    void sqlFailureRollsBackEarlierUpdatesHoursAndAudit() throws Exception {
        run(fixture(), true);
        sql("ALTER TABLE store_business_hours ADD CONSTRAINT test_reject_friday CHECK(day_of_week <> 'FRIDAY')");
        var batch = batch("55001,롤백 검증점,서울,주소,0,0,\n55003,새 합성점,서울,주소,0,0,\n",
            "55001,MONDAY,11:00,12:00,false\n55003,FRIDAY,,,true\n");
        assertThatThrownBy(() -> run(batch, true)).isInstanceOf(SQLException.class);
        assertThat(scalar("SELECT name FROM stores WHERE id=55001")).isEqualTo("합성 검증점, \"가\"");
        assertThat(scalar("SELECT count(*) FROM stores")).isEqualTo("2");
        assertThat(scalar("SELECT open_time FROM store_business_hours WHERE day_of_week='MONDAY'")).isEqualTo("20:00:00");
        assertThat(scalar("SELECT count(*) FROM store_csv_imports")).isEqualTo("1");
    }

    @Test
    void commitFailureAlsoRollsBackSequenceRestart() throws Exception {
        sql("CREATE FUNCTION reject_commit() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'synthetic failure'; END $$; "
            + "CREATE CONSTRAINT TRIGGER reject_commit AFTER INSERT ON store_csv_imports DEFERRABLE INITIALLY DEFERRED "
            + "FOR EACH ROW EXECUTE FUNCTION reject_commit()");
        assertThatThrownBy(() -> run(fixture(), true)).isInstanceOf(SQLException.class);
        assertThat(scalar("SELECT count(*) FROM stores")).isEqualTo("0");
        assertThat(scalar("SELECT count(*) FROM store_business_hours")).isEqualTo("0");
        assertThat(scalar("SELECT count(*) FROM store_csv_imports")).isEqualTo("0");
        assertThat(scalar("SELECT last_value||':'||is_called FROM stores_id_seq")).isEqualTo("1:false");
    }

    @Test
    void sequenceNeverMovesBackAndIncompatibleSequenceFailsAtomically() throws Exception {
        sql("SELECT setval('stores_id_seq',90000,true)");
        run(fixture(), true);
        assertThat(scalar("SELECT nextval('stores_id_seq')")).isEqualTo("90001");
        sql("ALTER SEQUENCE stores_id_seq CACHE 2");
        assertThatThrownBy(() -> run(batch("55004,합성점,서울,주소,0,0,\n", ""), true)).hasMessageContaining("sequence");
        assertThat(scalar("SELECT count(*) FROM stores")).isEqualTo("2");
        assertThat(scalar("SELECT count(*) FROM store_csv_imports")).isEqualTo("1");
    }

    @Test
    void readOnlyPrivilegeCanPreviewButCannotApply() throws Exception {
        String role = schema + "_reader";
        sql("CREATE ROLE " + role + "; GRANT USAGE ON SCHEMA " + schema + " TO " + role
            + "; GRANT SELECT ON stores TO " + role);
        try {
            try (var connection = connect(); var statement = connection.createStatement()) {
                statement.execute("SET ROLE " + role);
                assertThat(importer.execute(connection, schema, fixture(), false).newStores()).isEqualTo(2);
                assertThatThrownBy(() -> importer.execute(connection, schema, fixture(), true)).isInstanceOf(SQLException.class);
            }
            assertThat(scalar("SELECT count(*) FROM stores")).isEqualTo("0");
        } finally { sql("DROP OWNED BY " + role + "; DROP ROLE " + role); }
    }

    @Test
    void concurrentImportsWaitAndDoNotDuplicateRows() throws Exception {
        var batch = fixture();
        try (var executor = Executors.newFixedThreadPool(2); var blocker = connect(); var statement = blocker.createStatement()) {
            blocker.setAutoCommit(false);
            statement.execute("LOCK TABLE stores IN SHARE ROW EXCLUSIVE MODE");
            var started = new CountDownLatch(2);
            var first = executor.submit(() -> { started.countDown(); return run(batch, true); });
            var second = executor.submit(() -> { started.countDown(); return run(batch, true); });
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            // DB 관측으로 두 적재가 테이블 잠금을 기다리는 상태인지 확인한다.
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (Integer.parseInt(scalar("SELECT count(*) FROM pg_stat_activity WHERE wait_event_type='Lock' "
                + "AND query LIKE '%" + schema + "%stores%'").trim()) < 2 && System.nanoTime() < deadline) {
                Thread.sleep(20);
            }
            assertThat(scalar("SELECT count(*) FROM pg_stat_activity WHERE wait_event_type='Lock' "
                + "AND query LIKE '%" + schema + "%stores%'" )).isEqualTo("2");
            assertThat(first.isDone()).isFalse();
            assertThat(second.isDone()).isFalse();
            blocker.commit();
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
            assertThat(scalar("SELECT count(*) FROM stores")).isEqualTo("2");
            assertThat(scalar("SELECT count(*) FROM store_csv_imports")).isEqualTo("2");
        }
    }
}
