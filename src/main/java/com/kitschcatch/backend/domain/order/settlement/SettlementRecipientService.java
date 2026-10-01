// DB 트랜잭션 밖에서 토스 셀러를 검증한 뒤 수취인 연결을 저장한다.
package com.kitschcatch.backend.domain.order.settlement;
import com.kitschcatch.backend.domain.order.entity.SettlementRecipient;
import com.kitschcatch.backend.global.exception.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
@Service @RequiredArgsConstructor
public class SettlementRecipientService {
    private final SettlementRecipientTransactions transactions;
    private final SettlementGateway gateway;
    public SettlementRecipient bind(long userId,long sellerId,String id) {
        transactions.authorize(userId,sellerId);
        if(!gateway.configured()) throw new BusinessException(ErrorCode.SETTLEMENT_NOT_CONFIGURED);
        SettlementGateway.Recipient verified;
        try { verified=gateway.recipient(id); }
        catch(RuntimeException failure) { throw new BusinessException(ErrorCode.SETTLEMENT_PROVIDER_FAILED); }
        if(verified==null || !id.equals(verified.id())) throw new BusinessException(ErrorCode.SETTLEMENT_RECIPIENT_INVALID);
        return transactions.bind(userId,sellerId,verified);
    }
    public SettlementRecipient get(long userId,long sellerId) { return transactions.get(userId,sellerId); }
}
