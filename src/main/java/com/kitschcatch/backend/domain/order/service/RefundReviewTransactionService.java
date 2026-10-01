// 운영자 반품 검수와 구매자 철회를 주문 잠금 안에서 직렬화한다.
package com.kitschcatch.backend.domain.order.service;
import com.kitschcatch.backend.domain.order.dto.*;
import com.kitschcatch.backend.domain.order.entity.*;
import com.kitschcatch.backend.domain.order.repository.PaymentRepository;
import com.kitschcatch.backend.global.exception.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
@Service @RequiredArgsConstructor @EnableConfigurationProperties(RefundProperties.class)
public class RefundReviewTransactionService {
    private final OrderAccessService access;
    private final PaymentRepository payments;
    private final PaymentTransactionService paymentTransactions;
    private final RefundProperties properties;
    @Transactional
    public RefundTransactionService.Prepared review(long userId,String number,RefundDecisionRequest request,boolean approve) {
        var o=access.operatorOrder(userId,number,properties.operatorUserIds(),true,ErrorCode.REFUND_FORBIDDEN);
        var r=current(o,request.refundId());
        if(approve && !Boolean.TRUE.equals(request.returnReceived())) throw new BusinessException(ErrorCode.REFUND_RETURN_REQUIRED);
        if(r.sameDecision(request.reason().strip(),approve)) return null;
        requireRequested(o,r);
        r.review(userId,request.reason().strip(),approve);
        if(!approve) return null;
        var p=payments.findByOrderIdForUpdate(o.getId()).orElseThrow(()->new BusinessException(ErrorCode.ORDER_INVALID_STATE));
        long buyerId=o.getUser().getId();
        return new RefundTransactionService.Prepared(buyerId,p.getPaymentId(),paymentTransactions.startRefund(buyerId,p.getPaymentId()));
    }
    @Transactional
    public void withdraw(long userId,String number,RefundWithdrawalRequest request) {
        var o=access.lock(userId,number); access.requireBuyer(o,userId);
        var r=current(o,request.refundId());
        if(r.getStatus()==OrderRefund.Status.WITHDRAWN) return;
        requireRequested(o,r); r.withdraw();
    }
    private OrderRefund current(PurchaseOrder o,String id) {
        if(o.getRefund()==null) throw new BusinessException(ErrorCode.REFUND_NOT_FOUND);
        if(!o.getRefund().getRefundId().equals(id)) throw new BusinessException(ErrorCode.REFUND_CONFLICT);
        return o.getRefund();
    }
    private void requireRequested(PurchaseOrder o,OrderRefund r) {
        var p=payments.findByOrderIdForUpdate(o.getId()).orElse(null);
        if(o.getOrderStatus()!=OrderStatus.PAID || o.getShipment()==null || r.getStatus()!=OrderRefund.Status.REQUESTED
            || p==null || p.getPaymentStatus()!=PaymentStatus.SUCCESS || p.isRecoveryReviewRequired())
            throw new BusinessException(ErrorCode.ORDER_INVALID_STATE);
    }
}
