// 등록할 비공개 사진을 읽어 형식·크기와 검수 파일 해시를 확인한다.
package com.kitschcatch.backend.tools.storecontent;

import static com.kitschcatch.backend.tools.storecontent.StoreContentBatch.require;

import java.io.IOException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;

final class StoreContentPhotoVerifier {
    private static final int MAX_BYTES = 5 * 1024 * 1024;
    private final S3Client s3;
    private final String bucket;

    StoreContentPhotoVerifier(S3Client s3, String bucket) {
        this.s3 = s3;
        this.bucket = bucket;
    }

    void verify(StoreContentBatch.Photo photo) throws IOException {
        String key = photo.row().key();
        try (var object = s3.getObject(GetObjectRequest.builder().bucket(bucket).key(key).build())) {
            try {
                Long length = object.response().contentLength();
                require(length != null && length > 0 && length <= MAX_BYTES, "사진 크기는 1바이트 이상 5 MiB 이하여야 합니다.");
                byte[] bytes = object.readNBytes(length.intValue() + 1);
                String type = object.response().contentType();
                require(bytes.length == length && matchesFormat(key, type, bytes), "사진의 실제 형식 또는 크기가 일치하지 않습니다.");
                require(StoreContentBatch.sha256(bytes).equals(photo.sha256()), "사진이 검수한 파일과 일치하지 않습니다.");
            } catch (IOException | RuntimeException failure) {
                // 읽기 제한을 넘겼을 때 스트림 close가 남은 바이트를 읽지 않도록 중단한다.
                object.abort();
                throw failure;
            }
        }
    }

    private boolean matchesFormat(String key, String type, byte[] bytes) {
        if (key.endsWith(".jpg")) {
            return "image/jpeg".equals(type) && beginsAt(bytes, 0, 0xff, 0xd8, 0xff);
        }
        if (key.endsWith(".png")) {
            return "image/png".equals(type) && beginsAt(bytes, 0, 0x89, 'P', 'N', 'G', 13, 10, 26, 10);
        }
        return "image/webp".equals(type) && beginsAt(bytes, 0, 'R', 'I', 'F', 'F')
            && beginsAt(bytes, 8, 'W', 'E', 'B', 'P');
    }

    private boolean beginsAt(byte[] bytes, int offset, int... prefix) {
        if (bytes.length < offset + prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) {
            if (Byte.toUnsignedInt(bytes[offset + i]) != prefix[i]) return false;
        }
        return true;
    }
}
