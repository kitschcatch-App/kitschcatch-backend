// 상세 정보 등록 파일의 오입력과 사진 바이트 불일치를 검증한다.
package com.kitschcatch.backend.tools.storecontent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import tools.jackson.core.JacksonException;

class StoreContentBatchTest {
    static final String KEY = "stores/1/00000000-0000-4000-8000-000000000001.png";
    static final String SECOND = "stores/1/00000000-0000-4000-8000-000000000002.png";
    static final String HASH = "a".repeat(64);
    private static final String SAMPLE = """
        {
          "storeId":1, "expectedName":"첫 매장", "expectedAddress":"첫 주소",
          "description":"  소개\\n둘째 줄  ", "descriptionSourceUrl":"https://example.test/shop",
          "images":[%s],
          "amenities":[{"code":"PARKING","status":"AVAILABLE","sourceUrl":"https://example.test/parking"}]
        }
        """.formatted(photo(KEY, 0));

    @Test
    void distinguishesOmissionAndClearAndValidatesUnicodeLength() {
        var parsed = batch(SAMPLE).stores().getFirst();
        assertThat(parsed.description()).isEqualTo("소개\n둘째 줄");
        assertThat(parsed.photos()).hasSize(1);
        var clear = batch("""
            {"storeId":1,"expectedName":"첫 매장","expectedAddress":"첫 주소","description":null,"images":[]}
            """).stores().getFirst();
        assertThat(clear.replaceDescription()).isTrue();
        assertThat(clear.description()).isNull();
        assertThat(clear.photos()).isEmpty();
        assertThat(clear.amenities()).isNull();
        assertThatThrownBy(() -> batch(SAMPLE.replace("소개", "한".repeat(1001)))).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "wrong-key", "duplicate-sort", "duplicate-key", "too-many", "invalid-status", "unknown-amenity",
        "duplicate-amenity", "missing-source", "http-source", "unverified-rights", "non-array", "fractional-id",
        "duplicate-field", "duplicate-store", "unknown-field", "trailing-document", "invalid-date"
    })
    void rejectsAmbiguousOrUnverifiedInputs(String invalid) {
        String row = switch (invalid) {
            case "wrong-key" -> SAMPLE.replace("stores/1/", "stores/2/");
            case "duplicate-sort" -> SAMPLE.replace(photo(KEY, 0), photo(KEY, 0) + "," + photo(SECOND, 0));
            case "duplicate-key" -> SAMPLE.replace(photo(KEY, 0), photo(KEY, 0) + "," + photo(KEY, 1));
            case "too-many" -> SAMPLE.replace(photo(KEY, 0), (photo(KEY, 0) + ",").repeat(10) + photo(KEY, 0));
            case "invalid-status" -> SAMPLE.replace("AVAILABLE", "UNKNOWN");
            case "unknown-amenity" -> SAMPLE.replace("PARKING", "BAD_CODE");
            case "duplicate-amenity" -> SAMPLE.replace("\"amenities\":[", "\"amenities\":["
                + "{\"code\":\"PARKING\",\"status\":\"UNAVAILABLE\",\"sourceUrl\":\"https://example.test\"},");
            case "missing-source" -> SAMPLE.replace("\"descriptionSourceUrl\":\"https://example.test/shop\",", "");
            case "http-source" -> SAMPLE.replace("https://", "http://");
            case "unverified-rights" -> SAMPLE.replace("\"rightsBasis\":\"직접 촬영\",", "");
            case "non-array" -> SAMPLE.replace("[" + photo(KEY, 0) + "]", "null");
            case "fractional-id" -> SAMPLE.replace("\"storeId\":1", "\"storeId\":1.2");
            case "duplicate-field" -> SAMPLE.replace("\"storeId\":1", "\"storeId\":1,\"storeId\":2");
            case "duplicate-store" -> SAMPLE + "," + SAMPLE;
            case "unknown-field" -> SAMPLE.replace("\"description\"", "\"descripton\"");
            default -> SAMPLE;
        };
        String input = document(row) + (invalid.equals("trailing-document") ? "{}" : "");
        if (invalid.equals("invalid-date")) input = input.replace("2026-11-05T05:00:00Z", "어제");
        String invalidInput = input;
        assertThatThrownBy(() -> parse(invalidInput)).isInstanceOfAny(IllegalArgumentException.class, JacksonException.class);
    }

    @Test
    void verifiesPrivateObjectTypeLengthAndChecksum() throws Exception {
        byte[] png = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aZxkAAAAASUVORK5CYII=");
        var s3 = mock(S3Client.class);
        var request = GetObjectRequest.builder().bucket("store-test-bucket").key(KEY).build();
        var photo = new StoreContentBatch.Photo(new StoreContentBatch.PhotoRow(KEY, 0, null, null, null),
            StoreContentBatch.sha256(png));
        var verifier = new StoreContentPhotoVerifier(s3, "store-test-bucket");
        when(s3.getObject(request)).thenReturn(image(png, "image/png", png.length));
        verifier.verify(photo);
        when(s3.getObject(request)).thenReturn(image(png, "text/html", png.length));
        assertThatThrownBy(() -> verifier.verify(photo)).hasMessageContaining("형식");
        when(s3.getObject(request)).thenReturn(image(png, "image/png", 6 * 1024 * 1024));
        assertThatThrownBy(() -> verifier.verify(photo)).hasMessageContaining("크기");
        when(s3.getObject(request)).thenReturn(image(png, "image/png", png.length));
        assertThatThrownBy(() -> verifier.verify(new StoreContentBatch.Photo(photo.row(), HASH))).hasMessageContaining("검수");
    }

    private ResponseInputStream<GetObjectResponse> image(byte[] bytes, String type, int length) {
        return new ResponseInputStream<>(GetObjectResponse.builder().contentType(type).contentLength((long) length).build(),
            new ByteArrayInputStream(bytes));
    }

    static String photo(String key, int order) {
        return """
            {"objectKey":"%s","sortOrder":%d,"rightsBasis":"직접 촬영","sha256":"%s"}""".formatted(key, order, HASH);
    }

    static StoreContentBatch batch(String rows) {
        return parse(document(rows));
    }

    private static String document(String rows) {
        return """
            {"reviewer":"검수자","reviewedAt":"2026-11-05T05:00:00Z","stores":[%s]}""".formatted(rows);
    }

    private static StoreContentBatch parse(String json) {
        return StoreContentBatch.parse(json.getBytes(StandardCharsets.UTF_8));
    }
}
