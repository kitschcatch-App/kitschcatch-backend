// 환불 요청 저장과 트랜잭션 밖의 PG 전액 취소 호출을 연결한다.
package com.kitschcatch.backend.domain.order.service;
import com.kitschcatch.backend.domain.order.dto.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
@Service
@RequiredArgsConstructor
public class RefundService {
    private final RefundTransactionService transactions;
    private final PaymentService payments;
    public RefundResponse request(long userId,String number,RefundRequest request) {
        var prepared=transactions.prepare(userId,number,request);
        if(prepared!=null) payments.executeCancellation(userId,prepared.paymentId(),prepared.context(),request.reason().strip());
        return transactions.get(userId,number);
    }
    public RefundResponse get(long userId,String number) { return transactions.get(userId,number); }
}
