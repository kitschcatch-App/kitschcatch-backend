// 환불 요청을 주문 잠금 안에서 저장하고 배송 전 PG 취소를 선점한다.
package com.kitschcatch.backend.domain.order.service;
import com.kitschcatch.backend.domain.order.dto.*;
import com.kitschcatch.backend.domain.order.entity.*;
import com.kitschcatch.backend.domain.order.repository.PaymentRepository;
import com.kitschcatch.backend.global.exception.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
@Service
@RequiredArgsConstructor
public class RefundTransactionService {
    private final OrderAccessService access;
    private final PaymentRepository payments;
    private final PaymentTransactionService paymentTransactions;
    public record Prepared(String paymentId,PaymentOperationContext context) {}
    @Transactional
    public Prepared prepare(long userId,String number,RefundRequest request) {
        var o=access.lock(userId,number); access.requireBuyer(o,userId);
        if (!o.getAmount().equals(request.amount())) throw new BusinessException(ErrorCode.REFUND_AMOUNT_INVALID);
        if(o.getRefund()!=null) {
            if(!o.getRefund().matches(request.amount(),request.reason().strip())) throw new BusinessException(ErrorCode.REFUND_CONFLICT);
            return null;
        }
        var p=payments.findByOrderIdForUpdate(o.getId()).orElse(null);
        if(o.getOrderStatus()!=OrderStatus.PAID || o.getCancelReason()!=null || p==null
            || p.getPaymentStatus()!=PaymentStatus.SUCCESS || p.isRecoveryReviewRequired())
            throw new BusinessException(ErrorCode.ORDER_INVALID_STATE);
        var refund=new OrderRefund(request.amount(),request.reason().strip()); o.requestRefund(refund);
        // 반품 확인 정책과 승인 경로가 정해지기 전에는 발송한 상품의 자동 환불을 실행하지 않는다.
        if(o.getShipment()!=null) return null;
        refund.start();
        return new Prepared(p.getPaymentId(),paymentTransactions.startRefund(userId,p.getPaymentId()));
    }
    @Transactional(readOnly=true)
    public RefundResponse get(long userId,String number) {
        var o=access.read(userId,number);
        if(o.getRefund()==null) throw new BusinessException(ErrorCode.REFUND_NOT_FOUND);
        return RefundResponse.from(o);
    }
}
