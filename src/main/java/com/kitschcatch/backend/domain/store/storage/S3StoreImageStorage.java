// 비공개 S3 매장 사진에 대한 GET 서명 URL을 발급한다.
package com.kitschcatch.backend.domain.store.storage;

import com.kitschcatch.backend.domain.post.storage.S3Properties;
import com.kitschcatch.backend.domain.store.service.StoreImageKey;
import com.kitschcatch.backend.domain.store.service.StoreImageStorage;
import com.kitschcatch.backend.global.exception.BusinessException;
import com.kitschcatch.backend.global.exception.ErrorCode;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

@Component
public class S3StoreImageStorage implements StoreImageStorage {
    private final S3Presigner presigner;
    private final S3Properties properties;

    public S3StoreImageStorage(S3Presigner presigner, S3Properties properties) {
        this.presigner = presigner;
        this.properties = properties;
    }

    @Override
    public String imageUrl(long storeId, String objectKey) {
        if (!StoreImageKey.matches(storeId, objectKey)) {
            throw new IllegalStateException("등록된 매장 사진 키가 올바르지 않습니다.");
        }
        if (!StringUtils.hasText(properties.bucket())) {
            throw new BusinessException(ErrorCode.S3_BUCKET_NOT_CONFIGURED);
        }
        return presigner.presignGetObject(GetObjectPresignRequest.builder()
            .signatureDuration(properties.downloadUrlTtl())
            .getObjectRequest(GetObjectRequest.builder().bucket(properties.bucket()).key(objectKey).build())
            .build()).url().toString();
    }
}
