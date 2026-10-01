// 주문 잠금 안에서 미결제 취소 또는 PG 취소 시도를 선점한다.
package com.kitschcatch.backend.domain.order.service;
import com.kitschcatch.backend.domain.order.dto.CancelOrderResponse;
import com.kitschcatch.backend.domain.order.entity.*;
import com.kitschcatch.backend.domain.order.repository.*;
import com.kitschcatch.backend.global.exception.*;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
@Service
@RequiredArgsConstructor
public class OrderCancellationTransactionService {
    private final OrderAccessService access;
    private final PaymentRepository payments;
    private final PaymentAttemptRepository attempts;
    private final PaymentTransactionService paymentTransactions;
    public record Prepared(String paymentId, PaymentOperationContext context) {}
    @Transactional
    public Prepared prepare(long userId, String number, String reason) {
        var order = access.lock(userId, number);
        access.requireBuyer(order, userId);
        if (order.getOrderStatus() == OrderStatus.CANCELED) return null;
        if (order.getCancelReason() != null && !order.getCancelReason().equals(reason))
            throw new BusinessException(ErrorCode.ORDER_REQUEST_CONFLICT);
        var payment = payments.findByOrderIdForUpdate(order.getId()).orElse(null);
        if (payment != null && payment.isRecoveryReviewRequired()) throw new BusinessException(ErrorCode.ORDER_INVALID_STATE);
        if (order.getOrderStatus() == OrderStatus.PENDING) {
            if (payment != null && payment.getPaymentStatus() != PaymentStatus.READY && payment.getPaymentStatus() != PaymentStatus.FAILED)
                throw new BusinessException(ErrorCode.ORDER_INVALID_STATE);
            if (payment != null) {
                var active = attempts.findByPaymentIdAndAttemptStatusIn(payment.getId(),
                    List.of(PaymentAttemptStatus.PREPARED, PaymentAttemptStatus.PROCESSING, PaymentAttemptStatus.UNKNOWN));
                if (active.stream().anyMatch(a -> a.getAttemptStatus() != PaymentAttemptStatus.PREPARED))
                    throw new BusinessException(ErrorCode.ORDER_INVALID_STATE);
                active.forEach(PaymentAttempt::markExpired);
            }
            order.requestCancellation(reason);
            if (payment == null) order.cancel(); else payment.cancel();
            order.getPost().releaseOrder(order.getOrderNumber());
            return null;
        }
        if (!order.canCancelBeforeShipment() || payment == null) throw new BusinessException(ErrorCode.ORDER_INVALID_STATE);
        if (payment.getPaymentStatus() == PaymentStatus.PROCESSING && payment.getProcessingOperation() == PaymentOperation.CANCEL)
            return null;
        order.requestCancellation(reason);
        return new Prepared(payment.getPaymentId(), paymentTransactions.startCancel(userId, payment.getPaymentId()));
    }
    @Transactional(readOnly = true)
    public CancelOrderResponse response(long userId, String number) {
        var order = access.read(userId, number);
        return new CancelOrderResponse(number, order.getOrderStatus(), order.getCanceledAt(),
            order.getOrderStatus() != OrderStatus.CANCELED);
    }
}
