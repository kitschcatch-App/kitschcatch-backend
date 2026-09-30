// 거래 당사자의 조회 권한을 확인하고 주문 스냅샷과 DB의 결제 상태를 읽는다.
package com.kitschcatch.backend.domain.order.service;

import com.kitschcatch.backend.domain.order.dto.OrderDetailResponse;
import com.kitschcatch.backend.domain.order.repository.OrderHistoryRepository;
import com.kitschcatch.backend.domain.post.service.PostImageStorage;
import com.kitschcatch.backend.domain.user.repository.UserRepository;
import com.kitschcatch.backend.global.exception.BusinessException;
import com.kitschcatch.backend.global.exception.ErrorCode;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class OrderHistoryService {
    private static final Pattern ORDER_NUMBER = Pattern.compile("ORD-[A-Z0-9][A-Z0-9-]{0,45}");
    private final OrderHistoryRepository repository;
    private final UserRepository users;
    private final PostImageStorage images;

    public OrderHistoryService(OrderHistoryRepository repository, UserRepository users, PostImageStorage images) {
        this.repository = repository;
        this.users = users;
        this.images = images;
    }

    public OrderDetailResponse detail(long userId, String orderNumber) {
        if (orderNumber == null || !ORDER_NUMBER.matcher(orderNumber).matches()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST);
        }
        requireUser(userId);
        var row = repository.findDetail(orderNumber)
            .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_NOT_FOUND));
        var order = row.order();
        if (!order.getUser().getId().equals(userId) && !order.getSellerId().equals(userId)) {
            throw new BusinessException(ErrorCode.ORDER_ACCESS_DENIED);
        }
        String key = order.getPostThumbnailKey();
        return OrderDetailResponse.from(row, key == null ? null : images.imageUrl(key));
    }

    private void requireUser(long userId) {
        if (!users.existsById(userId)) throw new BusinessException(ErrorCode.INVALID_AUTH_TOKEN);
    }
}
