// 리뷰 사진 저장소의 임시 업로드, 검증 원본 읽기와 최종 파일 수명주기를 정의한다.
package com.kitschcatch.backend.domain.store.service;

import java.time.Instant;
import java.util.Map;

public interface StoreReviewImageStorage {
    record Upload(String url, Instant expiresAt, Map<String, String> headers) {}
    Upload upload(String key, String contentType, long size);
    byte[] read(String key, String contentType, long expectedSize);
    void publish(String key, byte[] png);
    String imageUrl(String key);
    void delete(String key);
}
