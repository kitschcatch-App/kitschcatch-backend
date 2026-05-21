package com.kitschcatch.backend.domain.payment.client;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

@Component
public class TossPaymentRestClient implements TossPaymentClient {

	private static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

	private final TossPaymentProperties properties;
	private final RestClient restClient;

	public TossPaymentRestClient(TossPaymentProperties properties) {
		this.properties = properties;
		SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
		requestFactory.setConnectTimeout(properties.connectTimeout());
		requestFactory.setReadTimeout(properties.readTimeout());
		this.restClient = RestClient.builder()
			.baseUrl(properties.baseUrl())
			.requestFactory(requestFactory)
			.defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
			.build();
	}

	@Override
	public TossPaymentResult confirmPayment(String paymentKey, String orderId, Long amount, String idempotencyKey) {
		requireSecretKey();
		TossPaymentResponse response = post(
			"/v1/payments/confirm",
			new TossConfirmPaymentRequest(paymentKey, orderId, amount),
			idempotencyKey,
			TossPaymentResponse.class
		);
		return toResult(response);
	}

	@Override
	public TossPaymentResult getPayment(String paymentKey) {
		requireSecretKey();
		try {
			TossPaymentResponse response = restClient.get()
				.uri("/v1/payments/{paymentKey}", paymentKey)
				.header(HttpHeaders.AUTHORIZATION, authorizationHeader())
				.retrieve()
				.body(TossPaymentResponse.class);
			return toResult(response);
		} catch (RestClientResponseException exception) {
			throw new TossPaymentException(
				"토스페이먼츠 API 응답 오류: " + exception.getStatusCode(),
				exception.getStatusCode().value(),
				exception
			);
		} catch (RestClientException exception) {
			throw new TossPaymentException("토스페이먼츠 API 호출 오류", exception);
		}
	}

	@Override
	public TossPaymentResult cancelPayment(String paymentKey, String cancelReason, String idempotencyKey) {
		requireSecretKey();
		TossPaymentResponse response = post(
			"/v1/payments/{paymentKey}/cancel",
			new TossCancelPaymentRequest(cancelReason),
			idempotencyKey,
			TossPaymentResponse.class,
			paymentKey
		);
		return toResult(response);
	}

	private <T> T post(String uri, Object body, String idempotencyKey, Class<T> responseType, Object... uriVariables) {
		try {
			return restClient.post()
				.uri(uri, uriVariables)
				.header(HttpHeaders.AUTHORIZATION, authorizationHeader())
				.header(IDEMPOTENCY_KEY_HEADER, idempotencyKey)
				.body(body)
					.retrieve()
					.body(responseType);
			} catch (RestClientResponseException exception) {
				throw new TossPaymentException(
					"토스페이먼츠 API 응답 오류: " + exception.getStatusCode(),
					exception.getStatusCode().value(),
					exception
				);
			} catch (RestClientException exception) {
				throw new TossPaymentException("토스페이먼츠 API 호출 오류", exception);
			}
	}

	private TossPaymentResult toResult(TossPaymentResponse response) {
		if (response == null) {
			throw new TossPaymentException("토스페이먼츠 응답이 비어 있습니다.");
		}
		return new TossPaymentResult(
			response.paymentKey(),
			response.orderId(),
			response.totalAmount(),
			response.status(),
			response.lastTransactionKey()
		);
	}

	private String authorizationHeader() {
		String token = Base64.getEncoder()
			.encodeToString((properties.secretKey() + ":").getBytes(StandardCharsets.UTF_8));
		return "Basic " + token;
	}

	private void requireSecretKey() {
		if (!properties.hasSecretKey()) {
			throw new TossPaymentException("토스페이먼츠 시크릿 키 설정이 필요합니다.");
		}
	}

	private record TossConfirmPaymentRequest(
		String paymentKey,
		String orderId,
		Long amount
	) {
	}

	private record TossCancelPaymentRequest(
		String cancelReason
	) {
	}

	private record TossPaymentResponse(
		String paymentKey,
		String orderId,
		Long totalAmount,
		String status,
		String lastTransactionKey,
		Object checkout,
		Object cancels
	) {
	}
}
