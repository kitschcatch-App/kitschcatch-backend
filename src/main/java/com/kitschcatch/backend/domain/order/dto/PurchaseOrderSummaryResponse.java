// 구매 목록에 주문 당시 상품·금액과 현재 주문·결제 상태를 표시한다.
package com.kitschcatch.backend.domain.order.dto;

import com.kitschcatch.backend.domain.order.entity.OrderStatus;
import com.kitschcatch.backend.domain.order.entity.PaymentStatus;
import com.kitschcatch.backend.domain.order.repository.OrderHistoryRow;
import java.time.LocalDateTime;

public record PurchaseOrderSummaryResponse(String orderId, Long postId, String postTitle, Long amount,
    OrderStatus orderStatus, PaymentStatus paymentStatus, LocalDateTime orderedAt) {
    public static PurchaseOrderSummaryResponse from(OrderHistoryRow row) {
        var order = row.order();
        return new PurchaseOrderSummaryResponse(order.getOrderNumber(), order.getSnapshotPostId(),
            order.getPostTitle(), order.getAmount(), order.getOrderStatus(),
            row.payment() == null ? null : row.payment().getPaymentStatus(), order.getCreatedAt());
    }
}
