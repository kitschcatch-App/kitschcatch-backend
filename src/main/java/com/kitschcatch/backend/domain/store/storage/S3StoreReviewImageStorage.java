// 매장 리뷰 사진 경로와 오류 정책을 공통 S3 저장소에 연결한다.
package com.kitschcatch.backend.domain.store.storage;

import com.kitschcatch.backend.domain.post.storage.S3Properties;
import com.kitschcatch.backend.domain.store.service.StoreReviewImageStorage;
import com.kitschcatch.backend.global.exception.ErrorCode;
import com.kitschcatch.backend.global.image.PrivateImageObjects;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

@Component
public class S3StoreReviewImageStorage extends PrivateImageObjects implements StoreReviewImageStorage {
    private final S3Properties properties;

    public S3StoreReviewImageStorage(S3Client client, S3Presigner presigner, S3Properties properties) {
        super(client, presigner, properties,
            Pattern.compile("store-review-uploads/[1-9][0-9]*/[0-9a-f-]{36}"),
            Pattern.compile("store-review-images/[1-9][0-9]*/[1-9][0-9]*/[0-9a-f-]{36}[.]png"),
            new Errors(ErrorCode.STORE_REVIEW_IMAGE_INVALID, ErrorCode.STORE_REVIEW_IMAGE_NOT_UPLOADED,
                ErrorCode.STORE_REVIEW_IMAGE_STORAGE_UNAVAILABLE));
        this.properties = properties;
    }

    @Override
    public StoreReviewImageStorage.Upload upload(String key, String contentType, long size) {
        var signed = signUpload(key, contentType, size);
        return new StoreReviewImageStorage.Upload(signed.url(), signed.expiresAt(), signed.headers());
    }

    @Override
    public void publish(String key, byte[] png) {
        publishPng(key, png);
    }

    @Override
    public String imageUrl(String key) {
        return signDownload(key, properties.downloadUrlTtl()).url();
    }
}
