// 주문 잠금으로 구매 확정 자격과 주문별 한 번의 상대방 평가를 보장한다.
package com.kitschcatch.backend.domain.review.service;

import com.kitschcatch.backend.domain.order.dto.OrderHistoryPageResponse;
import com.kitschcatch.backend.domain.order.entity.OrderStatus;
import com.kitschcatch.backend.domain.order.service.OrderAccessService;
import com.kitschcatch.backend.domain.review.dto.*;
import com.kitschcatch.backend.domain.review.entity.TransactionReview;
import com.kitschcatch.backend.domain.review.repository.*;
import com.kitschcatch.backend.domain.user.repository.UserRepository;
import com.kitschcatch.backend.global.exception.*;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class TransactionReviewService {
    private final OrderAccessService access;
    private final TransactionReviewRepository reviews;
    private final TrustInfoRepository trust;
    private final UserRepository users;

    @Transactional
    public ReviewResponse create(long userId, String orderNumber, CreateReviewRequest request) {
        var order = access.lock(userId, orderNumber);
        if (order.getOrderStatus() != OrderStatus.PURCHASE_CONFIRMED || order.hasActiveRefund())
            throw new BusinessException(ErrorCode.REVIEW_NOT_ALLOWED);
        if (reviews.existsByOrderIdAndAuthorId(order.getId(), userId))
            throw new BusinessException(ErrorCode.REVIEW_ALREADY_EXISTS);
        long recipientId = order.getUser().getId().equals(userId) ? order.getSellerId() : order.getUser().getId();
        if (recipientId == userId) throw new BusinessException(ErrorCode.REVIEW_NOT_ALLOWED);
        String content = request.content().strip();
        if (content.isEmpty()) throw new BusinessException(ErrorCode.INVALID_INPUT_VALUE);
        var review = new TransactionReview(order, users.getReferenceById(userId),
            users.getReferenceById(recipientId), request.rating().intValueExact(), content);
        return ReviewResponse.from(reviews.saveAndFlush(review));
    }

    @Transactional(readOnly = true)
    public List<ReviewResponse> forOrder(long userId, String orderNumber) {
        var order = access.read(userId, orderNumber);
        return reviews.findByOrderIdOrderByCreatedAtAscIdAsc(order.getId()).stream()
            .map(ReviewResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public OrderHistoryPageResponse<ReviewResponse> forUser(long actorId, long userId, int page, int size) {
        requireUsers(actorId, userId);
        if (page < 0 || page > 10000 || size < 1 || size > 100)
            throw new BusinessException(ErrorCode.INVALID_INPUT_VALUE);
        return OrderHistoryPageResponse.from(reviews.findByRecipientId(userId,
            PageRequest.of(page, size, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"))))
            .map(ReviewResponse::from));
    }

    @Transactional(readOnly = true)
    public TrustInfoResponse trustInfo(long actorId, long userId) {
        requireUsers(actorId, userId);
        return trust.find(userId);
    }

    private void requireUsers(long actorId, long userId) {
        if (!users.existsById(actorId)) throw new BusinessException(ErrorCode.INVALID_AUTH_TOKEN);
        if (userId <= 0) throw new BusinessException(ErrorCode.INVALID_INPUT_VALUE);
        if (!users.existsById(userId)) throw new BusinessException(ErrorCode.USER_NOT_FOUND);
    }
}
