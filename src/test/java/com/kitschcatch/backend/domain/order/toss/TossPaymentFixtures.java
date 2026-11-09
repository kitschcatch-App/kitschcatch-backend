// 실제 토스 카드 승인 응답에 맞춘 테스트 데이터를 제공한다.
package com.kitschcatch.backend.domain.order.toss;

public final class TossPaymentFixtures {

	private TossPaymentFixtures() {
	}

	public static TossPaymentResponse cardApproval(String paymentKey, String orderId, Long amount) {
		return new TossPaymentResponse(paymentKey, orderId, amount, "DONE", amount, null, null, null,
			"카드", new TossPaymentResponse.Card("신용"));
	}
}
