// 사용자 잠금 안에서 거래를 검사하고 계정 종료·인증 해제·판매글 숨김을 원자적으로 처리한다.
package com.kitschcatch.backend.domain.user.service;

import com.kitschcatch.backend.domain.order.repository.PurchaseOrderRepository;
import com.kitschcatch.backend.domain.order.repository.PaymentRepository;
import com.kitschcatch.backend.domain.user.repository.UserRepository;
import com.kitschcatch.backend.domain.user.repository.UserWithdrawalRepository;
import com.kitschcatch.backend.domain.auth.repository.RefreshTokenRepository;
import com.kitschcatch.backend.domain.post.repository.PostRepository;
import com.kitschcatch.backend.global.exception.BusinessException;
import com.kitschcatch.backend.global.exception.ErrorCode;
import java.time.Instant;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class UserWithdrawalService {
    private final UserRepository users;
    private final UserWithdrawalRepository trades;
    private final RefreshTokenRepository refreshTokens;
    private final PostRepository posts;
    private final PurchaseOrderRepository orders;
    private final PaymentRepository payments;

    @Transactional
    public void withdraw(Long userId) {
        var user = users.findByIdForUpdate(userId)
            .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_AUTH_TOKEN));
        if (!user.isActive()) return;
        // 기존 결제·복구 경로와 같은 상품 → 주문 → 결제 순서로 결과 확정을 기다린다.
        for (Long id : trades.findAffectedPostIds(userId)) posts.findByIdForUpdate(id);
        for (Long id : trades.findOrderIds(userId)) {
            orders.findByIdForUpdate(id);
            payments.findByOrderIdForUpdate(id);
        }
        if (trades.countBlockingTrades(userId) > 0) {
            throw new BusinessException(ErrorCode.USER_WITHDRAWAL_BLOCKED);
        }
        user.withdraw(Instant.now());
        refreshTokens.deleteAllByUserId(userId);
        posts.hideByUserId(userId, LocalDateTime.now());
    }
}
