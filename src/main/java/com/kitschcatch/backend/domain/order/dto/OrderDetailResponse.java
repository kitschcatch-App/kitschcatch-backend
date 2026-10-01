// 주문 스냅샷과 현재 주문·결제 상태를 거래 상세 응답으로 반환한다.
package com.kitschcatch.backend.domain.order.dto;

import com.kitschcatch.backend.domain.order.entity.*;
import com.kitschcatch.backend.domain.order.repository.OrderHistoryRow;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

public record OrderDetailResponse(
    @Schema(description = "주문 생성 응답의 공개 주문 번호. 내부 숫자 PK가 아닙니다.") String orderId,
    OrderStatus status, Long amount, OrderedPost post,
    OrderParticipantResponse buyer, OrderParticipantResponse seller,
    @Schema(description = "결제가 없는 과거 주문은 null", nullable = true) OrderedPayment payment,
    LocalDateTime orderedAt, LocalDateTime reservationExpiresAt
) {
    public static OrderDetailResponse from(OrderHistoryRow row, String thumbnailUrl) {
        var order = row.order();
        return new OrderDetailResponse(order.getOrderNumber(), order.getOrderStatus(), order.getAmount(),
            new OrderedPost(order.getSnapshotPostId(), order.getPostTitle(), thumbnailUrl),
            new OrderParticipantResponse(order.getUser().getId(), order.getBuyerNickname()),
            new OrderParticipantResponse(order.getSellerId(), order.getSellerNickname()),
            row.payment() == null ? null : OrderedPayment.from(row.payment()),
            order.getCreatedAt(), order.getReservationExpiresAt());
    }

    public record OrderedPost(Long id, String title,
        @Schema(description = "주문 당시 대표 이미지 키로 만든 URL. 이미지가 없으면 null", nullable = true) String thumbnailUrl) {}

    public record OrderedPayment(String paymentId, PaymentMethod method, PaymentStatus status,
        PaymentOperation processingOperation, PaymentRecoveryState recoveryState,
        LocalDateTime approvedAt, LocalDateTime canceledAt, LocalDateTime lastVerifiedAt) {
        static OrderedPayment from(Payment payment) {
            return new OrderedPayment(payment.getPaymentId(), payment.getPaymentMethod(), payment.getPaymentStatus(),
                payment.getProcessingOperation(), payment.getRecoveryState(), payment.getApprovedAt(),
                payment.getCanceledAt(), payment.getLastVerifiedAt());
        }
    }
}
