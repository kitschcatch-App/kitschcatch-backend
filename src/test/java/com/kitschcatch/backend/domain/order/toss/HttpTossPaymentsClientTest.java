// 토스페이먼츠 HTTP 클라이언트의 요청 형식을 검증하는 테스트
package com.kitschcatch.backend.domain.order.toss;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withBadRequest;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.kitschcatch.backend.global.exception.BusinessException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class HttpTossPaymentsClientTest {

	private MockRestServiceServer server;
	private HttpTossPaymentsClient client;

	@BeforeEach
	void setUp() {
		RestClient.Builder builder = RestClient.builder();
		server = MockRestServiceServer.bindTo(builder).build();
		client = new HttpTossPaymentsClient(
			builder,
			new TossPaymentsProperties("https://api.tosspayments.com", "test_sk_secret")
		);
	}

	@Test
	@DisplayName("결제 승인 요청은 Basic 인증과 토스 승인 본문을 전송한다")
	void confirmSendsTossConfirmRequest() {
		server.expect(once(), requestTo("https://api.tosspayments.com/v1/payments/confirm"))
			.andExpect(method(HttpMethod.POST))
			.andExpect(header("Authorization", basicAuth()))
			.andExpect(jsonPath("$.paymentKey").value("toss-payment-key"))
			.andExpect(jsonPath("$.orderId").value("ORD-123"))
			.andExpect(jsonPath("$.amount").value(650000))
			.andRespond(withSuccess("""
				{
				  "paymentKey": "toss-payment-key",
				  "orderId": "ORD-123",
				  "totalAmount": 650000,
				  "status": "DONE"
				}
				""", MediaType.APPLICATION_JSON));

		TossPaymentResponse response = client.confirm(
			new TossPaymentConfirmRequest("toss-payment-key", "ORD-123", 650000L)
		);

		assertThat(response.status()).isEqualTo("DONE");
		server.verify();
	}

	@Test
	@DisplayName("결제 취소 요청은 paymentKey 경로와 취소 사유를 전송한다")
	void cancelSendsTossCancelRequest() {
		server.expect(once(), requestTo("https://api.tosspayments.com/v1/payments/toss-payment-key/cancel"))
			.andExpect(method(HttpMethod.POST))
			.andExpect(header("Authorization", basicAuth()))
			.andExpect(jsonPath("$.cancelReason").value("고객 요청"))
			.andRespond(withSuccess("""
				{
				  "paymentKey": "toss-payment-key",
				  "orderId": "ORD-123",
				  "totalAmount": 650000,
				  "status": "CANCELED"
				}
				""", MediaType.APPLICATION_JSON));

		TossPaymentResponse response = client.cancel(
			new TossPaymentCancelRequest("toss-payment-key", "고객 요청")
		);

		assertThat(response.status()).isEqualTo("CANCELED");
		server.verify();
	}

	@Test
	@DisplayName("결제 승인 실패는 토스 에러 응답 바디를 예외 메시지로 보존한다")
	void confirmFailureKeepsTossErrorBody() {
		String errorBody = "{\"code\":\"ALREADY_APPROVED\",\"message\":\"이미 승인된 결제입니다.\"}";
		server.expect(once(), requestTo("https://api.tosspayments.com/v1/payments/confirm"))
			.andExpect(method(HttpMethod.POST))
			.andExpect(header("Authorization", basicAuth()))
			.andRespond(withBadRequest()
				.contentType(MediaType.APPLICATION_JSON)
				.body(errorBody));

		assertThatThrownBy(() -> client.confirm(
			new TossPaymentConfirmRequest("toss-payment-key", "ORD-123", 650000L)
		))
			.isInstanceOf(BusinessException.class)
			.hasMessage(errorBody);
		server.verify();
	}

	private String basicAuth() {
		String token = Base64.getEncoder()
			.encodeToString("test_sk_secret:".getBytes(StandardCharsets.UTF_8));
		return "Basic " + token;
	}
}
