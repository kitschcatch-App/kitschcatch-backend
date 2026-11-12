// 환불 요청을 주문 잠금 안에서 저장하고 배송 전 PG 취소를 선점한다.
package com.kitschcatch.backend.domain.order.service;
import com.kitschcatch.backend.domain.notification.NotificationEvents.TradeChanged;
import com.kitschcatch.backend.domain.notification.NotificationType;
import com.kitschcatch.backend.domain.order.dto.*;
import com.kitschcatch.backend.domain.order.entity.*;
import com.kitschcatch.backend.domain.order.repository.PaymentRepository;
import com.kitschcatch.backend.global.exception.*;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
@Service
@RequiredArgsConstructor
public class RefundTransactionService {
    private final OrderAccessService access;
    private final ApplicationEventPublisher notifications;
    private final PaymentRepository payments;
    private final PaymentTransactionService paymentTransactions;
    private final RefundProperties properties;
    private final RefundImageService images;
    public record Prepared(long buyerId,String paymentId,PaymentOperationContext context,String reason) {}
    @Transactional
    public Prepared prepare(long userId,String number,RefundRequest request) {
        var o=access.lock(userId,number); access.requireBuyer(o,userId);
        if (!o.getAmount().equals(request.amount())) throw new BusinessException(ErrorCode.REFUND_AMOUNT_INVALID);
        var imageIds = request.validatedImageIds();
        if(o.getRefund()!=null) {
            if(!o.getRefund().matches(request.amount(),request.reason().strip())
                || !images.attachedIds(o).equals(imageIds)) throw new BusinessException(ErrorCode.REFUND_CONFLICT);
            return null;
        }
        var p=payments.findByOrderIdForUpdate(o.getId()).orElse(null);
        requireRefundable(o, p);
        var refund=new OrderRefund(request.amount(),request.reason().strip()); o.requestRefund(refund);
        images.attach(o, imageIds);
        // 발송한 상품은 운영자가 실물 수령을 확인한 뒤 별도 승인한다.
        Prepared prepared = null;
        if (o.getShipment()==null) {
            refund.start();
            prepared = new Prepared(userId,p.getPaymentId(),
                paymentTransactions.startRefund(userId,p.getPaymentId()),refund.getReason());
        }
        notifications.publishEvent(new TradeChanged(o, NotificationType.REFUND_REQUESTED));
        return prepared;
    }
    static void requireRefundable(PurchaseOrder order, Payment payment) {
        if (order.getOrderStatus() != OrderStatus.PAID || order.getCancelReason() != null || payment == null
                || payment.getPaymentStatus() != PaymentStatus.SUCCESS || payment.isRecoveryReviewRequired()) {
            throw new BusinessException(ErrorCode.ORDER_INVALID_STATE);
        }
    }

    @Transactional(readOnly=true)
    public RefundResponse get(long userId,String number) {
        var o=properties.operatorUserIds().contains(userId)
            ?access.operatorOrder(userId,number,properties.operatorUserIds(),false,ErrorCode.REFUND_FORBIDDEN):access.read(userId,number);
        if(o.getRefund()==null) throw new BusinessException(ErrorCode.REFUND_NOT_FOUND);
        return RefundResponse.from(o, images.responseImages(o));
    }
}
