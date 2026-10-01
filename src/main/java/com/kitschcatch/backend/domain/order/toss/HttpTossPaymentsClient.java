// RestClient로 토스페이먼츠 결제 API를 호출하는 구현체
package com.kitschcatch.backend.domain.order.toss;

import com.kitschcatch.backend.global.exception.BusinessException;
import com.kitschcatch.backend.global.exception.ErrorCode;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

@Component
public class HttpTossPaymentsClient implements TossPaymentsClient {

	private static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

	private final RestClient restClient;
	private final TossPaymentsProperties properties;

	public HttpTossPaymentsClient(RestClient.Builder restClientBuilder, TossPaymentsProperties properties) {
		this.restClient = restClientBuilder
			.baseUrl(properties.baseUrl())
			.build();
		this.properties = properties;
	}

	@Override
	public TossPaymentResponse confirm(TossPaymentConfirmRequest request) {
		try {
			return restClient.post()
				.uri("/v1/payments/confirm")
				.header(HttpHeaders.AUTHORIZATION, properties.authorizationHeader())
				.header(IDEMPOTENCY_KEY_HEADER, request.idempotencyKey() == null
					? "confirm-" + request.paymentKey() : request.idempotencyKey())
				.contentType(MediaType.APPLICATION_JSON)
				.body(request)
				.retrieve()
				.body(TossPaymentResponse.class);
		} catch (IllegalStateException exception) {
			throw new BusinessException(ErrorCode.TOSS_PAYMENTS_NOT_CONFIGURED);
		} catch (RestClientResponseException exception) {
			throw responseFailure(exception, true);
		} catch (RestClientException exception) {
			throw new TossPaymentException("TRANSPORT_ERROR", false);
		}
	}

	@Override
	public TossPaymentResponse getPayment(String paymentKey) {
		try {
			return restClient.get()
				.uri("/v1/payments/{paymentKey}", paymentKey)
				.header(HttpHeaders.AUTHORIZATION, properties.authorizationHeader())
				.retrieve()
				.body(TossPaymentResponse.class);
		} catch (IllegalStateException exception) {
			throw new BusinessException(ErrorCode.TOSS_PAYMENTS_NOT_CONFIGURED);
		} catch (RestClientResponseException exception) {
			throw responseFailure(exception, false);
		} catch (RestClientException exception) {
			throw new TossPaymentException("TRANSPORT_ERROR", false);
		}
	}

	@Override
	public TossPaymentResponse getPaymentByOrderId(String orderId) {
		try {
			return restClient.get()
				.uri("/v1/payments/orders/{orderId}", orderId)
				.header(HttpHeaders.AUTHORIZATION, properties.authorizationHeader())
				.retrieve()
				.body(TossPaymentResponse.class);
		} catch (IllegalStateException exception) {
			throw new BusinessException(ErrorCode.TOSS_PAYMENTS_NOT_CONFIGURED);
		} catch (RestClientResponseException exception) {
			throw responseFailure(exception, false);
		} catch (RestClientException exception) {
			throw new TossPaymentException("TRANSPORT_ERROR", false);
		}
	}

	@Override
	public TossPaymentResponse cancel(TossPaymentCancelRequest request) {
		try {
			return restClient.post()
				.uri("/v1/payments/{paymentKey}/cancel", request.paymentKey())
				.header(HttpHeaders.AUTHORIZATION, properties.authorizationHeader())
				.header(IDEMPOTENCY_KEY_HEADER, request.idempotencyKey() == null
					? "cancel-" + request.paymentKey() : request.idempotencyKey())
				.contentType(MediaType.APPLICATION_JSON)
				.body(new TossCancelBody(request.cancelReason()))
				.retrieve()
				.body(TossPaymentResponse.class);
		} catch (IllegalStateException exception) {
			throw new BusinessException(ErrorCode.TOSS_PAYMENTS_NOT_CONFIGURED);
		} catch (RestClientResponseException exception) {
			throw responseFailure(exception, false);
		} catch (RestClientException exception) {
			throw new TossPaymentException("TRANSPORT_ERROR", false);
		}
	}

	private TossPaymentException responseFailure(RestClientResponseException exception, boolean confirmation) {
		String code = "UNKNOWN_PG_ERROR";
		try {
			JsonNode body = JsonMapper.builder().build().readTree(exception.getResponseBodyAsString());
			if (body != null && body.path("code").isString()) {
				String candidate = body.path("code").asString();
				if (candidate.matches("[A-Z][A-Z0-9_]{0,99}")) code = candidate;
			}
		} catch (RuntimeException ignored) {
			// 잘못된 오류 본문은 실패 확정 근거로 사용하지 않는다.
		}
		boolean rejected = confirmation && exception.getStatusCode().value() == 403
			&& ("REJECT_CARD_PAYMENT".equals(code) || "REJECT_CARD_COMPANY".equals(code));
		return new TossPaymentException(code, rejected);
	}

	private record TossCancelBody(String cancelReason) {
	}
}
