// 합성 CSV의 인용·UTF-8·null·중복·필드 계약과 명시적 CLI 옵션을 검증한다.
package com.kitschcatch.backend.tools.storecsv;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class StoreCsvBatchTest {
    @TempDir Path directory;
    static final String HEADER = "id,name,region,address,latitude,longitude,phone\n";
    static final String HOURS = "store_id,day_of_week,open_time,close_time,closed\n";
    static final StoreCsvBatch.Review REVIEW = new StoreCsvBatch.Review("합성 fixture", "검증자", Instant.parse("2026-01-01T00:00:00Z"));

    StoreCsvBatch batch(String stores, String hours) throws Exception {
        return StoreCsvBatch.load(Files.writeString(directory.resolve("stores.csv"), stores),
            Files.writeString(directory.resolve("hours.csv"), hours), REVIEW);
    }

    @Test
    void acceptsBomQuotedMultilineUtf8NullAndOvernight() throws Exception {
        var batch = batch("\uFEFF" + HEADER + "10,\"합성, \"\"매장\"\"\",서울,\"주소\n둘째 줄\",-90,180,\\N\n",
            HOURS + "10,MONDAY,20:00,02:00,false\n10,TUESDAY,00:00,00:00,false\n10,SUNDAY,\\N,,true\n");
        assertThat(batch.stores().getFirst().name()).isEqualTo("합성, \"매장\"");
        assertThat(batch.stores().getFirst().address()).contains("\n");
        assertThat(batch.stores().getFirst().phone()).isNull();
        assertThat(batch.hours()).hasSize(3);
        assertThat(batch.storesHash()).matches("[0-9a-f]{64}");
    }

    @ParameterizedTest
    @ValueSource(strings = {"0,a,서울,b,0,0,", "-1,a,서울,b,0,0,", "01,a,서울,b,0,0,",
        "9223372036854775807,a,서울,b,0,0,", "9999999999999999999999,a,서울,b,0,0,",
        "1, ,서울,b,0,0,", "1,a,서울, ,0,0,", "1,a,서울특별시,b,0,0,",
        "1,a,서울,b,NaN,0,", "1,a,서울,b,91,0,", "1,a,서울,b,0,-181,",
        "1,a,서울,b,1e0,0,", "1,a,서울,b,0,0, ", "1,a,서울,b,0,0,extra,column"})
    void rejectsInvalidStores(String row) {
        assertThatThrownBy(() -> batch(HEADER + row + "\n", HOURS)).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"1,monday,10:00,20:00,false", "1,MONDAY,24:00,20:00,false",
        "1,MONDAY,10:00:00,20:00,false", "1,MONDAY,1:00,20:00,false",
        "1,MONDAY,,20:00,false", "1,MONDAY,10:00,20:00,true", "1,MONDAY,,,FALSE"})
    void rejectsInvalidHours(String row) {
        assertThatThrownBy(() -> batch(HEADER + "1,a,서울,b,0,0,\n", HOURS + row + "\n"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsDuplicateRowsHeadersAndEmptyBatch() {
        assertThatThrownBy(() -> batch(HEADER + "1,a,서울,b,0,0,\n1,b,서울,b,0,0,\n", HOURS)).hasMessageContaining("중복");
        assertThatThrownBy(() -> batch(HEADER, HOURS + "1,MONDAY,,,true\n1,MONDAY,,,true\n")).hasMessageContaining("중복");
        assertThatThrownBy(() -> batch("name,id,region,address,latitude,longitude,phone\n", HOURS)).hasMessageContaining("헤더");
        assertThatThrownBy(() -> batch(HEADER, HOURS)).hasMessageContaining("행이 없습니다");
    }

    @Test
    void rejectsMalformedUtf8AndQuotes() throws Exception {
        var stores = Files.write(directory.resolve("bad.csv"), new byte[]{(byte) 0xc3, 0x28});
        var hours = Files.writeString(directory.resolve("hours.csv"), HOURS);
        assertThatThrownBy(() -> StoreCsvBatch.load(stores, hours, REVIEW)).isInstanceOf(java.io.IOException.class);
        assertThatThrownBy(() -> batch(HEADER + "1,\"unterminated", HOURS)).isInstanceOf(java.io.IOException.class);
    }

    @Test
    void requiresValidReviewMetadata() {
        assertThatThrownBy(() -> new StoreCsvBatch.Review(" ", "검증자", REVIEW.reviewedAt())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new StoreCsvBatch.Review("합성", "검증자", Instant.now().plusSeconds(60))).isInstanceOf(IllegalArgumentException.class);
    }

    static String[] args(String mode, String url) {
        return new String[]{"--stores", "/tmp/stores.csv", "--hours", "/tmp/hours.csv", "--jdbc-url", url,
            "--db-user", "operator", "--password-env", "ISSUE55_PASSWORD", "--schema", "public", "--mode", mode,
            "--source", "합성", "--reviewer", "검증자", "--reviewed-at", "2026-01-01T00:00:00Z"};
    }

    @Test
    void cliHasNoImplicitModeDatabaseOrCredentials() {
        assertThatThrownBy(() -> StoreCsvCommand.options(new String[0])).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> StoreCsvCommand.options(args("auto", "jdbc:postgresql://localhost/test"))).hasMessageContaining("mode");
        assertThatThrownBy(() -> StoreCsvCommand.options(args("apply", "jdbc:postgresql://localhost/test?password=secret"))).hasMessageContaining("JDBC");
        assertThatThrownBy(() -> StoreCsvCommand.options(args("apply", "jdbc:postgresql://localhost/test?currentSchema=other"))).hasMessageContaining("JDBC");
        assertThat(StoreCsvCommand.options(args("dry-run", "jdbc:postgresql://localhost:5432/test?sslmode=verify-full"))).containsEntry("mode", "dry-run");
        var duplicate = args("apply", "jdbc:postgresql://localhost/test");
        duplicate[0] = "--hours";
        assertThatThrownBy(() -> StoreCsvCommand.options(duplicate)).hasMessageContaining("중복");
    }
}
