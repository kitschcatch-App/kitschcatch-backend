// DB 잠금 밖에서 지급을 요청하고 반복 요청은 같은 지급 식별자로 조회한다.
package com.kitschcatch.backend.domain.order.settlement;
import com.kitschcatch.backend.domain.order.dto.SettlementResponse;
import com.kitschcatch.backend.global.exception.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
@Service @RequiredArgsConstructor
public class SettlementService {
    private final SettlementTransactionService transactions;
    private final SettlementGateway gateway;
    public SettlementResponse execute(long userId,String number) {
        var prepared=transactions.prepare(userId,number,gateway.configured());
        if(prepared!=null) {
            SettlementGateway.Result result;
            try {
                result=prepared.request()?gateway.request(prepared.command()):gateway.lookup(prepared.command());
            } catch(SettlementNotSubmittedException failure) {
                transactions.notSubmitted(userId,number,prepared.command().settlementId());
                throw new BusinessException(failure.errorCode());
            } catch(RuntimeException failure) {
                transactions.unknown(userId,number);
                throw new BusinessException(ErrorCode.SETTLEMENT_PROVIDER_FAILED);
            }
            transactions.finish(userId,number,result);
        }
        return transactions.get(userId,number);
    }
    public SettlementResponse get(long userId,String number) {return transactions.get(userId,number);}
}
