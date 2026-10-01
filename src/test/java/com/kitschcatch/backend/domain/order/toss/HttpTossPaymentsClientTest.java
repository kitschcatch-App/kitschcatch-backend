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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
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
			.andExpect(header("Idempotency-Key", "attempt-1"))
			.andExpect(jsonPath("$.paymentKey").value("toss-payment-key"))
			.andExpect(jsonPath("$.orderId").value("ORD-123"))
			.andExpect(jsonPath("$.amount").value(650000))
			.andExpect(jsonPath("$.idempotencyKey").doesNotExist())
			.andRespond(withSuccess("""
				{
				  "paymentKey": "toss-payment-key",
				  "orderId": "ORD-123",
				  "totalAmount": 650000,
				  "status": "DONE"
				}
				""", MediaType.APPLICATION_JSON));

		TossPaymentResponse response = client.confirm(
			new TossPaymentConfirmRequest("toss-payment-key", "ORD-123", 650000L, "attempt-1")
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
	@DisplayName("결제 조회 요청은 paymentKey 경로와 Basic 인증을 전송한다")
	void getPaymentSendsLookupRequest() {
		server.expect(once(), requestTo("https://api.tosspayments.com/v1/payments/toss-payment-key"))
			.andExpect(method(HttpMethod.GET))
			.andExpect(header("Authorization", basicAuth()))
			.andRespond(withSuccess("""
				{
				  "paymentKey": "toss-payment-key",
				  "orderId": "ORD-123",
				  "totalAmount": 650000,
				  "balanceAmount": 0,
				  "status": "CANCELED",
				  "approvedAt": "2026-09-16T10:15:30+09:00",
				  "canceledAt": "2026-09-16T10:20:30+09:00",
				  "lastTransactionKey": "transaction-key"
				}
				""", MediaType.APPLICATION_JSON));

		TossPaymentResponse response = client.getPayment("toss-payment-key");

		assertThat(response.status()).isEqualTo("CANCELED");
		assertThat(response.balanceAmount()).isZero();
		assertThat(response.approvedAt()).isEqualTo("2026-09-16T10:15:30+09:00");
		assertThat(response.canceledAt()).isEqualTo("2026-09-16T10:20:30+09:00");
		server.verify();
	}

	@Test
	@DisplayName("결제 키가 없는 결제는 주문 번호로 조회할 수 있다")
	void getPaymentByOrderIdSendsLookupRequest() {
		server.expect(once(), requestTo("https://api.tosspayments.com/v1/payments/orders/ORD-123"))
			.andExpect(method(HttpMethod.GET))
			.andExpect(header("Authorization", basicAuth()))
			.andRespond(withSuccess("""
				{
				  "paymentKey": "toss-payment-key",
				  "orderId": "ORD-123",
				  "totalAmount": 650000,
				  "status": "DONE"
				}
				""", MediaType.APPLICATION_JSON));

		TossPaymentResponse response = client.getPaymentByOrderId("ORD-123");

		assertThat(response.paymentKey()).isEqualTo("toss-payment-key");
		assertThat(response.status()).isEqualTo("DONE");
		server.verify();
	}

	@Test
	@DisplayName("결제 승인 실패는 정제된 코드만 보존하고 원문을 노출하지 않는다")
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
			.isInstanceOfSatisfying(TossPaymentException.class, exception -> {
				assertThat(exception.pgCode()).isEqualTo("ALREADY_APPROVED");
				assertThat(exception.confirmedRejection()).isFalse();
				assertThat(exception.getMessage()).doesNotContain(errorBody);
			});
		server.verify();
	}

	private String basicAuth() {
		String token = Base64.getEncoder()
			.encodeToString("test_sk_secret:".getBytes(StandardCharsets.UTF_8));
		return "Basic " + token;
	}

	@ParameterizedTest
	@CsvSource({
		"403,REJECT_CARD_PAYMENT,true", "403,REJECT_CARD_COMPANY,true",
		"400,REJECT_CARD_PAYMENT,false", "500,REJECT_CARD_COMPANY,false",
		"400,ALREADY_PROCESSED_PAYMENT,false", "400,ALREADY_PROCESSING_REQUEST,false",
		"400,PROVIDER_ERROR,false", "401,UNAUTHORIZED_KEY,false",
		"404,NOT_FOUND_PAYMENT,false", "429,TOO_MANY_REQUESTS,false", "500,UNKNOWN_PAYMENT_ERROR,false",
		"400,NEW_UNKNOWN_CODE,false"
	})
	void classifiesOnlyOfficialConfirmationRejections(int status, String code, boolean rejected) {
		server.expect(requestTo("https://api.tosspayments.com/v1/payments/confirm"))
			.andRespond(withStatus(HttpStatus.valueOf(status)).contentType(MediaType.APPLICATION_JSON)
				.body("{\"code\":\"" + code + "\",\"message\":\"민감한 원문\"}"));
		assertThatThrownBy(() -> client.confirm(new TossPaymentConfirmRequest("key", "ORD-1", 1L)))
			.isInstanceOfSatisfying(TossPaymentException.class, exception -> {
				assertThat(exception.pgCode()).isEqualTo(code);
				assertThat(exception.confirmedRejection()).isEqualTo(rejected);
				assertThat(exception.getMessage()).doesNotContain("민감한 원문", code);
			});
		server.verify();
	}

	@Test
	void lookupAndCancellationCannotTurnARejectionCodeIntoConfirmationFailure() {
		server.expect(requestTo("https://api.tosspayments.com/v1/payments/key"))
			.andRespond(withStatus(HttpStatus.FORBIDDEN).contentType(MediaType.APPLICATION_JSON)
				.body("{\"code\":\"REJECT_CARD_PAYMENT\"}"));
		server.expect(requestTo("https://api.tosspayments.com/v1/payments/key/cancel"))
			.andRespond(withStatus(HttpStatus.FORBIDDEN).contentType(MediaType.APPLICATION_JSON)
				.body("{\"code\":\"REJECT_CARD_COMPANY\"}"));
		assertThatThrownBy(() -> client.getPayment("key")).isInstanceOfSatisfying(TossPaymentException.class,
			exception -> assertThat(exception.confirmedRejection()).isFalse());
		assertThatThrownBy(() -> client.cancel(new TossPaymentCancelRequest("key", "고객 요청")))
			.isInstanceOfSatisfying(TossPaymentException.class, exception -> assertThat(exception.confirmedRejection()).isFalse());
		server.verify();
	}

	@Test
	void malformedBodyAndTransportTimeoutStayUncertain() {
		server.expect(requestTo("https://api.tosspayments.com/v1/payments/confirm"))
			.andRespond(withStatus(HttpStatus.FORBIDDEN).body("not-json"));
		server.expect(requestTo("https://api.tosspayments.com/v1/payments/key"))
			.andRespond(withException(new java.net.SocketTimeoutException("비밀 응답")));
		assertThatThrownBy(() -> client.confirm(new TossPaymentConfirmRequest("key", "ORD-1", 1L)))
			.isInstanceOfSatisfying(TossPaymentException.class, exception -> {
				assertThat(exception.pgCode()).isEqualTo("UNKNOWN_PG_ERROR");
				assertThat(exception.confirmedRejection()).isFalse();
			});
		assertThatThrownBy(() -> client.getPayment("key")).isInstanceOfSatisfying(TossPaymentException.class,
			exception -> {
				assertThat(exception.pgCode()).isEqualTo("TRANSPORT_ERROR");
				assertThat(exception.getMessage()).doesNotContain("비밀 응답");
			});
		server.verify();
	}

	@Test
	void configuredClientTimesOutAgainstLocalHttpServerWithoutExposingTransportDetails() throws Exception {
		var localPg = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
		var finish = new java.util.concurrent.CountDownLatch(1);
		localPg.createContext("/v1/payments/key", exchange -> {
			try {
				finish.await(5, java.util.concurrent.TimeUnit.SECONDS);
			} catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
			} finally {
				exchange.close();
			}
		});
		localPg.start();
		try {
			var configured = new HttpTossPaymentsClient(new TossPaymentsConfig().restClientBuilder(
				java.time.Duration.ofSeconds(1), java.time.Duration.ofMillis(50)),
				new TossPaymentsProperties("http://127.0.0.1:" + localPg.getAddress().getPort(), "local-test-key"));
			long startedAt = System.nanoTime();
			assertThatThrownBy(() -> configured.getPayment("key")).isInstanceOfSatisfying(TossPaymentException.class,
				exception -> {
					assertThat(exception.pgCode()).isEqualTo("TRANSPORT_ERROR");
					assertThat(exception.confirmedRejection()).isFalse();
				});
			assertThat(java.time.Duration.ofNanos(System.nanoTime() - startedAt)).isLessThan(java.time.Duration.ofSeconds(2));
		} finally {
			finish.countDown();
			localPg.stop(0);
		}
	}
}
