// 매장 소개 사진을 읽기 위한 임시 URL을 발급한다.
package com.kitschcatch.backend.domain.store.service;

public interface StoreImageStorage {
    String imageUrl(long storeId, String objectKey);
}
