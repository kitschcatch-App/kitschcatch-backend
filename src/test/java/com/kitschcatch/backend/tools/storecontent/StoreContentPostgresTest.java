// PostgreSQL에서 매장 상세 등록의 원자성·재실행·기존 CSV 호환성을 검증한다.
package com.kitschcatch.backend.tools.storecontent;

import static com.kitschcatch.backend.tools.storecontent.StoreContentBatchTest.KEY;
import static com.kitschcatch.backend.tools.storecontent.StoreContentBatchTest.SECOND;
import static com.kitschcatch.backend.tools.storecontent.StoreContentBatchTest.batch;
import static com.kitschcatch.backend.tools.storecontent.StoreContentBatchTest.photo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.kitschcatch.backend.tools.storecsv.StoreCsvBatch;
import com.kitschcatch.backend.tools.storecsv.StoreCsvImporter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named = "ISSUE83_TEST_DB_URL", matches = "jdbc:postgresql:.*")
class StoreContentPostgresTest {
    private final String schema = "issue83_" + UUID.randomUUID().toString().replace("-", "");
    private final StoreContentImporter importer = new StoreContentImporter();
    private static final String STORE = """
        INSERT INTO stores(id,name,region,address,latitude,longitude) VALUES(1,'첫 매장','서울','첫 주소',0,0);
        INSERT INTO store_business_hours VALUES(1,'MONDAY','10:00','20:00',false);
        INSERT INTO users VALUES(1);
        INSERT INTO store_favorites(user_id,store_id) VALUES(1,1);
        """;

    @BeforeEach
    void setup() throws Exception {
        try (var connection = connect(); var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + schema);
            connection.setSchema(schema);
            migrate(connection, "039_stores.sql");
            statement.execute("CREATE TABLE users(id BIGINT PRIMARY KEY)");
            migrate(connection, "041_store_favorites.sql");
            migrate(connection, "055_store_csv_imports.sql");
            statement.execute(STORE);
            migrate(connection, "083_store_details.sql");
        }
    }

    @AfterEach
    void cleanup() throws Exception {
        sql("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
    }

    @Test
    void dryRunDoesNotWriteAndApplyPreservesIdentityAcrossReorderAndRerun() throws Exception {
        var initial = full(0, 1);
        var preview = run(initial, false);
        assertThat(preview).isEqualTo(new StoreContentImporter.Result(1, 1, 1, 1, null));
        assertThat(scalar("SELECT description FROM stores")).isNull();
        assertThat(scalar("SELECT count(*) FROM store_content_imports")).isEqualTo("0");
        assertThat(scalar("SELECT last_value||':'||is_called FROM store_images_id_seq")).isEqualTo("1:false");

        var result = run(initial, true);
        assertThat(result.auditId()).isNotNull();
        assertThat(scalar("SELECT input_sha256 FROM store_content_imports")).isEqualTo(initial.sha256());
        assertThat(scalar("SELECT store_ids FROM store_content_imports")).isEqualTo("{1}");
        String first = scalar("SELECT id FROM store_images WHERE sort_order=0");
        String second = scalar("SELECT id FROM store_images WHERE sort_order=1");
        assertThat(run(initial, true).photoListsChanged()).isZero();
        assertThat(scalar("SELECT id FROM store_images WHERE sort_order=0")).isEqualTo(first);

        run(full(1, 0), true);
        assertThat(scalar("SELECT id FROM store_images WHERE sort_order=0")).isEqualTo(second);
        assertThat(scalar("SELECT id FROM store_images WHERE sort_order=1")).isEqualTo(first);
        try (var connection = connect()) { migrate(connection, "083_store_details.sql"); }
        assertThat(scalar("SELECT count(*) FROM store_images")).isEqualTo("2");

        run(batch("""
            {"storeId":1,"expectedName":"첫 매장","expectedAddress":"첫 주소","description":null,"images":[]}
            """), true);
        assertThat(scalar("SELECT description FROM stores")).isNull();
        assertThat(scalar("SELECT count(*) FROM store_images")).isEqualTo("0");
        assertThat(scalar("SELECT status FROM store_amenities")).isEqualTo("AVAILABLE");
        assertThat(scalar("SELECT count(*) FROM store_favorites")).isEqualTo("1");
        assertThat(scalar("SELECT open_time FROM store_business_hours")).isEqualTo("10:00:00");
    }

    @Test
    void mismatchedStoreRollsBackEarlierWritesAndAudit() throws Exception {
        var invalid = batch("""
            {"storeId":1,"expectedName":"첫 매장","expectedAddress":"첫 주소","description":"임시 소개",
             "descriptionSourceUrl":"https://example.test/shop"},
            {"storeId":2,"expectedName":"다른 매장","expectedAddress":"다른 주소","description":null}
            """);
        assertThatThrownBy(() -> run(invalid, true)).hasMessageContaining("storeId=2");
        assertThat(scalar("SELECT description FROM stores")).isNull();
        assertThat(scalar("SELECT count(*) FROM store_content_imports")).isEqualTo("0");
    }

    @Test
    void sqlRejectsBadImageAndAmenityDataEvenOutsideTheImporter() throws Exception {
        run(full(0, 1), true);
        assertSqlState("INSERT INTO store_images(store_id,object_key,sort_order) VALUES(1,'posts/1/photo.png',2)", "23514");
        assertSqlState("UPDATE store_images SET sort_order=10 WHERE sort_order=0", "23514");
        assertSqlState("UPDATE store_images SET sort_order=1 WHERE sort_order=0", "23505");
        assertSqlState("UPDATE store_amenities SET status='UNKNOWN'", "23514");
        assertSqlState("UPDATE store_amenities SET code='UNSUPPORTED'", "23514");
        assertSqlState("UPDATE store_amenities SET store_id=999", "23503");
    }

    @Test
    void baseCsvAndReviewedDetailsCanBeAppliedAndReappliedTogether() throws Exception {
        sql("DELETE FROM stores");
        Path base = Path.of("data/store-imports/2026-11-05");
        var csv = StoreCsvBatch.load(base.resolve("stores.csv"), base.resolve("business-hours.csv"),
            new StoreCsvBatch.Review("검증용 기존 배치", "검수자", Instant.parse("2026-11-05T05:00:00Z")));
        var details = StoreContentBatch.load(Path.of("data/store-content/2026-11-05/details.json"));
        applyCsv(csv);
        sql("INSERT INTO store_favorites(user_id,store_id) VALUES(1,1)");
        assertThat(run(details, false).descriptionsChanged()).isPositive();
        var applied = run(details, true);
        assertThat(applied.descriptionsChanged()).isPositive();
        assertThat(applied.amenityListsChanged()).isPositive();
        assertThat(run(details, true).descriptionsChanged()).isZero();

        String description = scalar("SELECT description FROM stores WHERE id=1");
        String facilities = scalar("SELECT count(*) FROM store_amenities");
        applyCsv(csv);
        assertThat(scalar("SELECT description FROM stores WHERE id=1")).isEqualTo(description);
        assertThat(scalar("SELECT count(*) FROM store_amenities")).isEqualTo(facilities);
        assertThat(scalar("SELECT count(*) FROM stores")).isEqualTo("30");
        assertThat(scalar("SELECT count(*) FROM store_business_hours")).isEqualTo("194");
        assertThat(scalar("SELECT count(*) FROM store_favorites")).isEqualTo("1");
    }

    private StoreContentBatch full(int firstOrder, int secondOrder) {
        return batch("""
            {"storeId":1,"expectedName":"첫 매장","expectedAddress":"첫 주소","description":"매장 소개",
             "descriptionSourceUrl":"https://example.test/shop","images":[%s,%s],
             "amenities":[{"code":"PARKING","status":"AVAILABLE","sourceUrl":"https://example.test/parking"}]}
            """.formatted(photo(KEY, firstOrder), photo(SECOND, secondOrder)));
    }

    private void applyCsv(StoreCsvBatch csv) throws Exception {
        try (var connection = connect()) { new StoreCsvImporter().execute(connection, schema, csv, true); }
    }

    private StoreContentImporter.Result run(StoreContentBatch batch, boolean apply) throws Exception {
        try (var connection = connect()) { return importer.execute(connection, schema, batch, apply); }
    }

    private void assertSqlState(String sql, String state) {
        assertThatThrownBy(() -> sql(sql)).isInstanceOfSatisfying(SQLException.class,
            failure -> assertThat(failure.getSQLState()).isEqualTo(state));
    }

    private Connection connect() throws SQLException {
        var connection = DriverManager.getConnection(System.getenv("ISSUE83_TEST_DB_URL"),
            System.getenv().getOrDefault("ISSUE83_TEST_DB_USERNAME", "postgres"),
            System.getenv().getOrDefault("ISSUE83_TEST_DB_PASSWORD", ""));
        connection.setSchema(schema);
        return connection;
    }

    private void migrate(Connection connection, String name) throws Exception {
        try (var stream = getClass().getResourceAsStream("/db/manual/" + name);
             var statement = connection.createStatement()) {
            statement.execute(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    private void sql(String sql) throws Exception {
        try (var connection = connect(); var statement = connection.createStatement()) { statement.execute(sql); }
    }

    private String scalar(String sql) throws Exception {
        try (var connection = connect(); var statement = connection.createStatement();
             var rows = statement.executeQuery(sql)) {
            rows.next();
            return rows.getString(1);
        }
    }
}
