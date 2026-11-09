// 반품 사진 전용 S3 경로와 오류 정책을 적용한다.
package com.kitschcatch.backend.domain.order.storage;

import com.kitschcatch.backend.domain.post.storage.S3Properties;
import com.kitschcatch.backend.global.exception.ErrorCode;
import com.kitschcatch.backend.global.image.PrivateImageObjects;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

@Component
public class RefundImageStorage extends PrivateImageObjects {
    public RefundImageStorage(S3Client client, S3Presigner presigner, S3Properties properties) {
        super(client, presigner, properties,
            Pattern.compile("refund-uploads/[1-9][0-9]*/[0-9a-f-]{36}"),
            Pattern.compile("refund-images/[1-9][0-9]*/[0-9a-f-]{36}[.]png"),
            new Errors(ErrorCode.REFUND_IMAGE_INVALID, ErrorCode.REFUND_IMAGE_NOT_UPLOADED,
                ErrorCode.REFUND_IMAGE_STORAGE_UNAVAILABLE));
    }
}
