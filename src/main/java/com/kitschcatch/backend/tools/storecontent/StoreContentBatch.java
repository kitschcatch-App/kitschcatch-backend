// 검수한 매장 상세 JSON을 읽고 변경 범위와 출처를 검증한다.
package com.kitschcatch.backend.tools.storecontent;

import com.kitschcatch.backend.domain.store.entity.StoreAmenityCode;
import com.kitschcatch.backend.domain.store.entity.StoreAmenityStatus;
import com.kitschcatch.backend.domain.store.service.StoreImageKey;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

record StoreContentBatch(String sha256, String reviewer, Instant reviewedAt, List<StoreEdit> stores) {
    private static final int MAX_INPUT = 1024 * 1024;
    private static final JsonMapper JSON = JsonMapper.builder()
        .enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY, DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .build();

    record StoreEdit(long id, String name, String address, boolean replaceDescription, String description,
                     List<Photo> photos, Map<StoreAmenityCode, Amenity> amenities) {}
    record Photo(PhotoRow row, String sha256) {}
    record PhotoRow(String key, int order, String caption, String credit, String sourceUrl) {}
    record Amenity(StoreAmenityStatus status, String note) {}

    static StoreContentBatch load(Path path) throws IOException {
        try (var input = Files.newInputStream(path)) {
            return parse(input.readNBytes(MAX_INPUT + 1));
        }
    }

    static StoreContentBatch parse(byte[] bytes) {
        require(bytes.length > 0 && bytes.length <= MAX_INPUT, "입력 파일은 1 MiB 이하여야 합니다.");
        var root = JSON.readTree(bytes);
        object(root, Set.of("reviewer", "reviewedAt", "stores"));
        var rows = array(root.get("stores"), 500);
        require(!rows.isEmpty(), "대상 매장이 하나 이상 필요합니다.");
        var stores = new ArrayList<StoreEdit>();
        var ids = new HashSet<Long>();
        for (var node : rows) {
            object(node, Set.of("storeId", "expectedName", "expectedAddress", "description", "descriptionSourceUrl",
                "images", "amenities"));
            var id = node.get("storeId");
            require(id != null && id.isIntegralNumber() && id.canConvertToLong() && id.longValue() > 0,
                "storeId는 양의 정수여야 합니다.");
            require(ids.add(id.longValue()), "같은 매장 ID가 중복되었습니다.");
            require(node.has("description") || node.has("images") || node.has("amenities"), "변경할 필드가 없습니다.");

            String description = optionalText(node.get("description"), 1000);
            if (description != null) url(node.get("descriptionSourceUrl"), true);
            else require(!node.has("descriptionSourceUrl"), "소개 없는 행에는 소개 출처를 지정할 수 없습니다.");
            stores.add(new StoreEdit(id.longValue(), text(node.get("expectedName"), 200),
                text(node.get("expectedAddress"), 500), node.has("description"), description,
                node.has("images") ? photos(id.longValue(), node.get("images")) : null,
                node.has("amenities") ? amenities(node.get("amenities")) : null));
        }
        stores.sort(Comparator.comparingLong(StoreEdit::id));
        Instant reviewedAt;
        try {
            reviewedAt = Instant.parse(text(root.get("reviewedAt"), 40));
        } catch (java.time.format.DateTimeParseException exception) {
            throw new IllegalArgumentException("reviewedAt은 ISO 8601 시각이어야 합니다.");
        }
        return new StoreContentBatch(sha256(bytes), text(root.get("reviewer"), 120), reviewedAt, List.copyOf(stores));
    }

    private static List<Photo> photos(long storeId, JsonNode node) {
        var photos = new ArrayList<Photo>();
        var keys = new HashSet<String>();
        var orders = new HashSet<Integer>();
        for (var row : array(node, 10)) {
            object(row, Set.of("objectKey", "sortOrder", "caption", "credit", "sourceUrl", "rightsBasis", "sha256"));
            String key = text(row.get("objectKey"), 180);
            require(StoreImageKey.matches(storeId, key) && keys.add(key), "사진 키의 매장 경로가 잘못되었거나 중복되었습니다.");
            var order = row.get("sortOrder");
            require(order != null && order.isIntegralNumber() && order.canConvertToInt() && order.intValue() >= 0
                && order.intValue() < 10 && orders.add(order.intValue()), "사진 순서는 0~9의 서로 다른 정수여야 합니다.");
            text(row.get("rightsBasis"), 500);
            String hash = text(row.get("sha256"), 64);
            require(hash.matches("[0-9a-f]{64}"), "사진 SHA-256은 64자리 소문자 16진수여야 합니다.");
            photos.add(new Photo(new PhotoRow(key, order.intValue(), optionalText(row.get("caption"), 200),
                optionalText(row.get("credit"), 200), url(row.get("sourceUrl"), false)), hash));
        }
        photos.sort(Comparator.comparingInt(photo -> photo.row().order()));
        return List.copyOf(photos);
    }

    private static Map<StoreAmenityCode, Amenity> amenities(JsonNode node) {
        var result = new EnumMap<StoreAmenityCode, Amenity>(StoreAmenityCode.class);
        for (var row : array(node, StoreAmenityCode.values().length)) {
            object(row, Set.of("code", "status", "note", "sourceUrl"));
            var code = enumValue(StoreAmenityCode.class, row.get("code"));
            var status = enumValue(StoreAmenityStatus.class, row.get("status"));
            require(status != StoreAmenityStatus.UNKNOWN, "미확인 편의시설은 목록에서 제외하세요.");
            url(row.get("sourceUrl"), true);
            require(result.putIfAbsent(code, new Amenity(status, optionalText(row.get("note"), 300))) == null,
                "같은 편의시설 코드가 중복되었습니다.");
        }
        return Map.copyOf(result);
    }

    private static <T extends Enum<T>> T enumValue(Class<T> type, JsonNode node) {
        try {
            return Enum.valueOf(type, text(node, 24));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("지원하지 않는 편의시설 코드 또는 상태입니다.");
        }
    }

    private static List<JsonNode> array(JsonNode node, int max) {
        require(node != null && node.isArray() && node.size() <= max, "목록 형식 또는 최대 개수를 확인하세요.");
        return List.copyOf(node.values());
    }

    private static void object(JsonNode node, Set<String> fields) {
        require(node != null && node.isObject() && fields.containsAll(node.propertyNames()),
            "객체 형식이 아니거나 알 수 없는 필드가 있습니다.");
    }

    private static String optionalText(JsonNode node, int max) {
        return node == null || node.isNull() ? null : text(node, max);
    }

    private static String text(JsonNode node, int max) {
        require(node != null && node.isTextual(), "필수 텍스트가 없거나 문자열 형식이 아닙니다.");
        String value = Normalizer.normalize(node.stringValue().replace("\r\n", "\n").strip(), Normalizer.Form.NFC);
        require(!value.isBlank() && value.codePointCount(0, value.length()) <= max
            && value.codePoints().noneMatch(c -> c != '\n' && (Character.isISOControl(c)
                || (c >= Character.MIN_SURROGATE && c <= Character.MAX_SURROGATE))),
            "빈 텍스트, 길이 제한 또는 사용할 수 없는 문자를 확인하세요.");
        return value;
    }

    private static String url(JsonNode node, boolean required) {
        String value = required ? text(node, 1000) : optionalText(node, 1000);
        if (value == null) return null;
        try {
            URI uri = URI.create(value);
            require("https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null && uri.getUserInfo() == null,
                "출처는 사용자 정보 없는 HTTPS URL이어야 합니다.");
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("출처는 사용자 정보 없는 HTTPS URL이어야 합니다.");
        }
        return value;
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256을 사용할 수 없습니다.", exception);
        }
    }

    static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }
}
