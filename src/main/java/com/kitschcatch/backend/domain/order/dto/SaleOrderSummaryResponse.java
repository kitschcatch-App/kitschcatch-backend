// 판매 목록에 주문 당시 구매자·상품·금액과 현재 거래 상태를 표시한다.
package com.kitschcatch.backend.domain.order.dto;

import com.kitschcatch.backend.domain.order.entity.OrderStatus;
import com.kitschcatch.backend.domain.order.entity.PaymentStatus;
import com.kitschcatch.backend.domain.order.repository.OrderHistoryRow;
import java.time.LocalDateTime;

public record SaleOrderSummaryResponse(String orderId, Long postId, String postTitle, OrderParticipantResponse buyer,
    Long amount, OrderStatus orderStatus, PaymentStatus paymentStatus, LocalDateTime orderedAt) {
    public static SaleOrderSummaryResponse from(OrderHistoryRow row) {
        var order = row.order();
        return new SaleOrderSummaryResponse(order.getOrderNumber(), order.getSnapshotPostId(), order.getPostTitle(),
            new OrderParticipantResponse(order.getUser().getId(), order.getBuyerNickname()),
            order.getAmount(), order.getOrderStatus(), row.payment() == null ? null : row.payment().getPaymentStatus(),
            order.getCreatedAt());
    }
}
