// 구매자의 사진 업로드를 검증하고 반품 접수와 조회에 연결한다.
package com.kitschcatch.backend.domain.order.service;

import static com.kitschcatch.backend.global.exception.ErrorCode.*;

import com.kitschcatch.backend.domain.order.dto.RefundImageDtos.*;
import com.kitschcatch.backend.domain.order.entity.PurchaseOrder;
import com.kitschcatch.backend.domain.order.entity.RefundImage;
import com.kitschcatch.backend.domain.order.entity.RefundImage.State;
import com.kitschcatch.backend.domain.order.repository.PaymentRepository;
import com.kitschcatch.backend.domain.order.repository.RefundImageRepository;
import com.kitschcatch.backend.domain.order.storage.RefundImageStorage;
import com.kitschcatch.backend.global.exception.BusinessException;
import com.kitschcatch.backend.global.image.ImageNormalizer;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class RefundImageService {
    private static final int DAILY_LIMIT = 100;
    private static final Duration DOWNLOAD_TTL = Duration.ofMinutes(5);
    private static final Map<String, Set<String>> EXTENSIONS = Map.of(
        "image/jpeg", Set.of("jpg", "jpeg"), "image/png", Set.of("png"), "image/webp", Set.of("webp"));

    private final OrderAccessService access;
    private final PaymentRepository payments;
    private final RefundImageRepository images;
    private final RefundImageStorage storage;
    private final ImageNormalizer normalizer = new ImageNormalizer(REFUND_IMAGE_INVALID, REFUND_IMAGE_BUSY);

    @Transactional
    public Uploads upload(long buyerId, String orderNumber, UploadRequest request) {
        var order = access.lock(buyerId, orderNumber);
        access.requireBuyer(order, buyerId);
        if (order.getRefund() != null) throw new BusinessException(REFUND_CONFLICT);
        RefundTransactionService.requireRefundable(order, payments.findByOrderIdForUpdate(order.getId()).orElse(null));

        Instant now = Instant.now();
        long issued = images.countByUploaderIdAndCreatedAtGreaterThanEqual(buyerId, now.minus(Duration.ofDays(1)));
        if (issued + request.images().size() > DAILY_LIMIT) throw new BusinessException(REFUND_IMAGE_BUSY);
        request.images().forEach(RefundImageService::validateExtension);
        var uploads = new ArrayList<Upload>();
        for (var file : request.images()) {
            var image = images.save(new RefundImage(order.getId(), buyerId, file.contentType(), file.fileSize(), now));
            var signed = storage.signUpload(image.getTemporaryKey(), file.contentType(), file.fileSize());
            uploads.add(new Upload(image.getId(), signed.url(), signed.expiresAt(), signed.headers()));
        }
        return new Uploads(uploads);
    }

    @Transactional
    public Completed complete(long buyerId, String orderNumber, long imageId) {
        var order = access.read(buyerId, orderNumber);
        access.requireBuyer(order, buyerId);
        var image = lockOwned(imageId, order.getId(), buyerId);
        if (image.getState() == State.ATTACHED) return completed(image);
        if (image.expired(Instant.now())) throw new BusinessException(REFUND_IMAGE_EXPIRED);
        if (image.getState() == State.READY) return completed(image);

        var bytes = storage.read(image.getTemporaryKey(), image.getContentType(), image.getExpectedSize());
        var clean = normalizer.normalize(bytes, image.getContentType());
        var digest = storage.publishPng(image.getObjectKey(), clean.png());
        image.ready(clean.width(), clean.height(), digest);
        return completed(image);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void attach(PurchaseOrder order, List<Long> ids) {
        var locked = new HashMap<Long, RefundImage>();
        // 서로 다른 제출 순서에서도 잠금 획득 순서를 통일한다.
        for (long id : ids.stream().sorted().toList()) {
            locked.put(id, lockOwned(id, order.getId(), order.getUser().getId()));
        }
        Instant now = Instant.now();
        for (int position = 0; position < ids.size(); position++) {
            var image = locked.get(ids.get(position));
            if (image.expired(now)) throw new BusinessException(REFUND_IMAGE_EXPIRED);
            if (image.getState() != State.READY) throw new BusinessException(REFUND_IMAGE_NOT_UPLOADED);
            image.attach(order.getRefund().getRefundId(), position);
        }
    }

    List<Long> attachedIds(PurchaseOrder order) {
        return attached(order).stream().map(RefundImage::getId).toList();
    }

    List<Image> responseImages(PurchaseOrder order) {
        return attached(order).stream().map(image -> {
            var signed = storage.signDownload(image.getObjectKey(), DOWNLOAD_TTL);
            return new Image(image.getId(), signed.url(), signed.expiresAt(), image.getSortOrder());
        }).toList();
    }

    private List<RefundImage> attached(PurchaseOrder order) {
        return images.findByOrderIdAndRefundIdOrderBySortOrder(order.getId(), order.getRefund().getRefundId());
    }

    private RefundImage lockOwned(long id, long orderId, long buyerId) {
        return images.lockOwned(id, orderId, buyerId)
            .orElseThrow(() -> new BusinessException(REFUND_IMAGE_NOT_FOUND));
    }

    private static Completed completed(RefundImage image) {
        return new Completed(image.getId(), image.getState(), image.getWidth(), image.getHeight());
    }

    private static void validateExtension(File file) {
        String name = file.fileName();
        int dot = name.lastIndexOf('.');
        if (dot <= 0 || !EXTENSIONS.getOrDefault(file.contentType(), Set.of())
                .contains(name.substring(dot + 1).toLowerCase(Locale.ROOT))) {
            throw new BusinessException(REFUND_IMAGE_INVALID);
        }
    }
}
