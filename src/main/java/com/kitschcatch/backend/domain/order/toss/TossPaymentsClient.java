// 토스페이먼츠 결제 승인·조회·취소를 호출하는 클라이언트 계약
package com.kitschcatch.backend.domain.order.toss;

public interface TossPaymentsClient {

	TossPaymentResponse confirm(TossPaymentConfirmRequest request);

	TossPaymentResponse getPayment(String paymentKey);

	TossPaymentResponse getPaymentByOrderId(String orderId);

	TossPaymentResponse cancel(TossPaymentCancelRequest request);
}
