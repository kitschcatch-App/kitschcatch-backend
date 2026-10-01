// 수취인 연결 권한을 확인하고 회원 잠금으로 중복 연결을 직렬화한다.
package com.kitschcatch.backend.domain.order.settlement;
import com.kitschcatch.backend.domain.order.entity.SettlementRecipient;
import com.kitschcatch.backend.domain.order.repository.SettlementRecipientRepository;
import com.kitschcatch.backend.domain.user.repository.UserRepository;
import com.kitschcatch.backend.global.exception.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
@Service @RequiredArgsConstructor
public class SettlementRecipientTransactions {
    private final UserRepository users;
    private final SettlementRecipientRepository recipients;
    private final SettlementProperties properties;
    @Transactional(readOnly=true)
    public void authorize(long userId,long sellerId) {
        if(!users.existsById(userId)) throw new BusinessException(ErrorCode.INVALID_AUTH_TOKEN);
        if(!properties.operatorUserIds().contains(userId)) throw new BusinessException(ErrorCode.SETTLEMENT_FORBIDDEN);
        if(!users.existsById(sellerId)) throw new BusinessException(ErrorCode.SETTLEMENT_RECIPIENT_NOT_FOUND);
    }
    @Transactional
    public SettlementRecipient bind(long userId,long sellerId,SettlementGateway.Recipient verified) {
        authorize(userId,sellerId);
        users.findByIdForUpdate(sellerId).orElseThrow(()->new BusinessException(ErrorCode.SETTLEMENT_RECIPIENT_NOT_FOUND));
        if(!SettlementGateway.sellerReference(sellerId).equals(verified.refSellerId()) || !"APPROVED".equals(verified.status()))
            throw new BusinessException(ErrorCode.SETTLEMENT_RECIPIENT_INVALID);
        var existing=recipients.findById(sellerId).orElse(null);
        if(existing!=null) {
            if(!existing.getProviderSellerId().equals(verified.id())) throw new BusinessException(ErrorCode.SETTLEMENT_RECIPIENT_INVALID);
            return existing;
        }
        return recipients.saveAndFlush(new SettlementRecipient(sellerId,verified.id(),userId));
    }
    @Transactional(readOnly=true)
    public SettlementRecipient get(long userId,long sellerId) {
        if(!users.existsById(userId)) throw new BusinessException(ErrorCode.INVALID_AUTH_TOKEN);
        if(userId!=sellerId && !properties.operatorUserIds().contains(userId)) throw new BusinessException(ErrorCode.SETTLEMENT_FORBIDDEN);
        return recipients.findById(sellerId).orElseThrow(()->new BusinessException(ErrorCode.SETTLEMENT_RECIPIENT_NOT_FOUND));
    }
}
