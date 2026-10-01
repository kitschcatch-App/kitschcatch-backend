// 구매자만 환불 요청이 없는 발송 주문을 확정하도록 직렬화한다.
package com.kitschcatch.backend.domain.order.service;
import com.kitschcatch.backend.domain.order.dto.PurchaseConfirmationResponse;
import com.kitschcatch.backend.domain.order.entity.*;
import com.kitschcatch.backend.domain.order.repository.PaymentRepository;
import com.kitschcatch.backend.global.exception.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
@Service
@RequiredArgsConstructor
public class PurchaseConfirmationService {
    private final OrderAccessService access;
    private final PaymentRepository payments;
    @Transactional
    public PurchaseConfirmationResponse confirm(long userId,String number) {
        var o=access.lock(userId,number);
        access.requireBuyer(o,userId);
        var p=payments.findByOrderIdForUpdate(o.getId()).orElse(null);
        if (p==null || p.getPaymentStatus()!=PaymentStatus.SUCCESS || p.isRecoveryReviewRequired()
            || o.hasActiveRefund() || o.getCancelReason()!=null || o.getShipment()==null
            || (o.getOrderStatus()!=OrderStatus.PAID && o.getOrderStatus()!=OrderStatus.PURCHASE_CONFIRMED))
            throw new BusinessException(ErrorCode.ORDER_INVALID_STATE);
        o.confirmPurchase();
        return new PurchaseConfirmationResponse(number,o.getOrderStatus(),o.getConfirmedAt());
    }
}
