// 검수 CSV의 고정 바이트와 헤더·필드·중복 계약을 DB 연결 전에 검증한다.
package com.kitschcatch.backend.tools.storecsv;

import java.io.IOException;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVRecord;

public record StoreCsvBatch(List<Store> stores, List<Hours> hours, String storesHash,
                            String hoursHash, Review review) {
    private static final Set<String> REGIONS = Set.of("서울", "부산", "대구", "인천", "광주", "대전",
        "울산", "세종", "경기", "강원", "충북", "충남", "전북", "전남", "경북", "경남", "제주");
    private static final long MAX_BYTES = 16 * 1024 * 1024;
    private static final int MAX_ROWS = 100_000;

    public StoreCsvBatch {
        stores = List.copyOf(stores);
        hours = List.copyOf(hours);
    }

    public record Store(long id, String name, String region, String address,
                        double latitude, double longitude, String phone) { }
    public record Hours(long storeId, DayOfWeek day, LocalTime open, LocalTime close, boolean closed) { }
    public record Review(String source, String reviewer, Instant reviewedAt) {
        public Review {
            text(source, 1000, "출처");
            text(reviewer, 200, "검수자");
            require(reviewedAt != null && !reviewedAt.isAfter(Instant.now()), "검수 시각은 현재 이하이어야 합니다.");
        }
    }

    public static StoreCsvBatch load(Path storesPath, Path hoursPath, Review review) throws IOException {
        var storesBytes = read(storesPath);
        var hoursBytes = read(hoursPath);
        var stores = new ArrayList<Store>();
        var storeIds = new HashSet<Long>();
        for (var row : rows(storesBytes, List.of("id", "name", "region", "address", "latitude", "longitude", "phone"))) {
            try {
                long id = id(row.get(0));
                require(storeIds.add(id), "중복 매장 ID입니다.");
                stores.add(new Store(id, text(row.get(1), 200, "이름"), region(row.get(2)),
                    text(row.get(3), 500, "주소"), coordinate(row.get(4), 90),
                    coordinate(row.get(5), 180), nullableText(row.get(6), 30)));
            } catch (IllegalArgumentException exception) {
                throw invalid("매장", row, exception);
            }
        }
        var hours = new ArrayList<Hours>();
        var keys = new HashSet<String>();
        for (var row : rows(hoursBytes, List.of("store_id", "day_of_week", "open_time", "close_time", "closed"))) {
            try {
                long id = id(row.get(0));
                var day = day(row.get(1));
                require(keys.add(id + ":" + day), "중복 매장·요일입니다.");
                require(Set.of("true", "false").contains(row.get(4)), "closed는 true 또는 false이어야 합니다.");
                boolean closed = Boolean.parseBoolean(row.get(4));
                var open = time(row.get(2));
                var close = time(row.get(3));
                require(closed ? open == null && close == null : open != null && close != null,
                    "휴무는 두 시간 null, 영업은 두 시간 필수입니다.");
                hours.add(new Hours(id, day, open, close, closed));
            } catch (IllegalArgumentException exception) {
                throw invalid("영업시간", row, exception);
            }
        }
        require(!stores.isEmpty() || !hours.isEmpty(), "적재할 행이 없습니다.");
        return new StoreCsvBatch(stores, hours, hash(storesBytes), hash(hoursBytes), review);
    }

    private static byte[] read(Path path) throws IOException {
        require(Files.isRegularFile(path) && Files.size(path) <= MAX_BYTES, "CSV는 16MiB 이하의 일반 파일이어야 합니다.");
        try (var stream = Files.newInputStream(path)) {
            byte[] bytes = stream.readNBytes((int) MAX_BYTES + 1);
            require(bytes.length <= MAX_BYTES, "CSV가 16MiB를 초과합니다.");
            return bytes;
        }
    }

    private static List<CSVRecord> rows(byte[] bytes, List<String> header) throws IOException {
        var decoder = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT);
        String csv = decoder.decode(ByteBuffer.wrap(bytes)).toString();
        if (csv.startsWith("\uFEFF")) csv = csv.substring(1);
        try (var parser = CSVFormat.RFC4180.parse(new StringReader(csv))) {
            var result = new ArrayList<CSVRecord>();
            var iterator = parser.iterator();
            require(iterator.hasNext(), "CSV 헤더가 없습니다.");
            require(iterator.next().toList().equals(header), "CSV 헤더 또는 순서가 계약과 다릅니다.");
            while (iterator.hasNext()) {
                var row = iterator.next();
                require(row.size() == header.size(), "CSV 레코드 " + row.getRecordNumber() + "의 열 수가 다릅니다.");
                require(result.size() < MAX_ROWS, "CSV는 파일당 100000행 이하이어야 합니다.");
                result.add(row);
            }
            return result;
        } catch (java.io.UncheckedIOException exception) {
            throw new IOException("CSV 인용 문법이 올바르지 않습니다.");
        }
    }

    private static long id(String value) {
        require(value.matches("[1-9][0-9]*"), "ID는 양의 십진 정수이어야 합니다.");
        try {
            long id = Long.parseLong(value);
            require(id < Long.MAX_VALUE, "ID는 sequence의 다음 값 공간을 남겨야 합니다.");
            return id;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("ID 범위를 초과합니다.");
        }
    }

    private static String text(String value, int max, String field) {
        require(value != null && !value.isBlank() && value.codePointCount(0, value.length()) <= max
            && value.indexOf('\0') < 0, field + " 값 또는 길이가 올바르지 않습니다.");
        return value;
    }

    private static String nullableText(String value, int max) {
        return isNull(value) ? null : text(value, max, "전화번호");
    }

    private static boolean isNull(String value) { return value.isEmpty() || value.equals("\\N"); }

    private static String region(String value) {
        require(REGIONS.contains(value), "표준 지역 약칭이어야 합니다.");
        return value;
    }

    private static double coordinate(String value, int max) {
        require(value.matches("[+-]?(?:[0-9]+(?:\\.[0-9]+)?|\\.[0-9]+)"), "좌표는 십진수이어야 합니다.");
        double number = Double.parseDouble(value);
        require(Double.isFinite(number) && Math.abs(number) <= max, "좌표 범위를 초과합니다.");
        return number;
    }

    private static LocalTime time(String value) {
        if (isNull(value)) return null;
        require(value.matches("(?:[01][0-9]|2[0-3]):[0-5][0-9]"), "시간은 00:00~23:59의 HH:mm이어야 합니다.");
        return LocalTime.parse(value);
    }

    private static DayOfWeek day(String value) {
        try { return DayOfWeek.valueOf(value); }
        catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("요일은 MONDAY~SUNDAY 중 하나이어야 합니다.");
        }
    }

    private static String hash(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }

    private static IllegalArgumentException invalid(String file, CSVRecord row, IllegalArgumentException cause) {
        return new IllegalArgumentException(file + " CSV 레코드 " + row.getRecordNumber() + ": " + cause.getMessage());
    }

    static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }
}
