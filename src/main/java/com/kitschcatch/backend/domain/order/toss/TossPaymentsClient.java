// 토스페이먼츠 결제 승인과 취소를 호출하는 클라이언트 계약
package com.kitschcatch.backend.domain.order.toss;

public interface TossPaymentsClient {

	TossPaymentResponse confirm(TossPaymentConfirmRequest request);

	TossPaymentResponse cancel(TossPaymentCancelRequest request);
}
