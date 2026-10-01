// 내부 정산 실행자의 요청을 직렬화하고 외부 지급 결과를 검증해 저장한다.
package com.kitschcatch.backend.domain.order.settlement;
import com.kitschcatch.backend.domain.order.dto.SettlementResponse;
import com.kitschcatch.backend.domain.order.entity.*;
import com.kitschcatch.backend.domain.order.repository.PaymentRepository;
import com.kitschcatch.backend.domain.order.service.OrderAccessService;
import com.kitschcatch.backend.global.exception.*;
import java.math.BigInteger;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
@Service @RequiredArgsConstructor
public class SettlementTransactionService {
    private final OrderAccessService access;
    private final PaymentRepository payments;
    private final SettlementProperties properties;
    public record Prepared(SettlementGateway.Command command, boolean request) {}
    @Transactional
    public Prepared prepare(long userId,String number,boolean providerConfigured) {
        var o=access.operatorOrder(userId,number,properties.operatorUserIds(),true);
        var p=payments.findByOrderIdForUpdate(o.getId()).orElse(null);
        if(o.getOrderStatus()!=OrderStatus.PURCHASE_CONFIRMED || o.hasActiveRefund() || o.getSettlement()==null
            || p==null || p.getPaymentStatus()!=PaymentStatus.SUCCESS || p.isRecoveryReviewRequired())
            throw new BusinessException(ErrorCode.SETTLEMENT_INVALID_STATE);
        var s=o.getSettlement();
        if(s.getStatus()==OrderSettlement.Status.COMPLETED) return null;
        if(!providerConfigured || properties.feeBasisPoints()==null) throw new BusinessException(ErrorCode.SETTLEMENT_NOT_CONFIGURED);
        boolean request=s.getStatus()==OrderSettlement.Status.WAITING;
        if(request) {
            long fee=BigInteger.valueOf(o.getAmount()).multiply(BigInteger.valueOf(properties.feeBasisPoints()))
                .divide(BigInteger.valueOf(10000)).longValueExact();
            s.start(o.getAmount()-fee,fee);
        }
        return new Prepared(new SettlementGateway.Command(s.getSettlementId(),o.getSellerId(),s.getAmount(),s.getFee(),"KRW"),request);
    }
    @Transactional
    public void finish(long userId,String number,SettlementGateway.Result result) {
        var o=access.operatorOrder(userId,number,properties.operatorUserIds(),true);
        var s=o.getSettlement();
        if(s==null || s.getStatus()==OrderSettlement.Status.WAITING) throw new BusinessException(ErrorCode.SETTLEMENT_INVALID_STATE);
        if(s.getStatus()==OrderSettlement.Status.COMPLETED) return;
        if(result==null || !s.getSettlementId().equals(result.settlementId()) || !s.getAmount().equals(result.amount())
            || !"KRW".equals(result.currency()) || result.status()==null) { s.unknown(); return; }
        switch(result.status()) {
            case PENDING -> s.pending();
            case FAILED -> s.failed();
            case COMPLETED -> {
                if(result.providerReference()==null || result.providerReference().isBlank() || result.providerReference().length()>200
                    || result.settledAt()==null) { s.unknown(); return; }
                s.complete(result.providerReference(),result.settledAt());
            }
        }
    }
    @Transactional
    public void unknown(long userId,String number) {
        var o=access.operatorOrder(userId,number,properties.operatorUserIds(),true);
        if(o.getSettlement()!=null) o.getSettlement().unknown();
    }
    @Transactional(readOnly=true)
    public SettlementResponse get(long userId,String number) {
        var o=properties.operatorUserIds().contains(userId)
            ? access.operatorOrder(userId,number,properties.operatorUserIds(),false) : access.read(userId,number);
        if(o.getSettlement()==null) throw new BusinessException(ErrorCode.SETTLEMENT_NOT_FOUND);
        return SettlementResponse.from(o);
    }
}
