// 취소 선점과 외부 PG 호출을 분리하고 최신 주문 상태를 반환한다.
package com.kitschcatch.backend.domain.order.service;
import com.kitschcatch.backend.domain.order.dto.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
@Service
@RequiredArgsConstructor
public class OrderCancellationService {
    private final OrderCancellationTransactionService transactions;
    private final PaymentService payments;
    public CancelOrderResponse cancel(long userId, String number, CancelOrderRequest request) {
        var prepared = transactions.prepare(userId, number, request.reason().strip());
        if (prepared != null) payments.executeCancellation(userId, prepared.paymentId(), prepared.context(), request.reason().strip());
        return transactions.response(userId, number);
    }
}
