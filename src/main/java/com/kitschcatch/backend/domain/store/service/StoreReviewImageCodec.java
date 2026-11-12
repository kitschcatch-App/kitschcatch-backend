// 매장 리뷰 사진에 공통 이미지 검증과 메타데이터 제거를 적용한다.
package com.kitschcatch.backend.domain.store.service;

import com.kitschcatch.backend.global.exception.ErrorCode;
import com.kitschcatch.backend.global.image.ImageNormalizer;
import org.springframework.stereotype.Component;

@Component
public class StoreReviewImageCodec extends ImageNormalizer {
    public StoreReviewImageCodec() {
        super(ErrorCode.STORE_REVIEW_IMAGE_INVALID, ErrorCode.STORE_REVIEW_IMAGE_BUSY);
    }
}
