// RestClient로 토스페이먼츠 결제 API를 호출하는 구현체
package com.kitschcatch.backend.domain.order.toss;

import com.kitschcatch.backend.global.exception.BusinessException;
import com.kitschcatch.backend.global.exception.ErrorCode;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

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
				.header(IDEMPOTENCY_KEY_HEADER, "confirm-" + request.paymentKey())
				.contentType(MediaType.APPLICATION_JSON)
				.body(request)
				.retrieve()
				.body(TossPaymentResponse.class);
		} catch (IllegalStateException exception) {
			throw new BusinessException(ErrorCode.TOSS_PAYMENTS_NOT_CONFIGURED);
		} catch (RestClientException exception) {
			throw new BusinessException(ErrorCode.TOSS_PAYMENTS_REQUEST_FAILED, exception.getMessage());
		}
	}

	@Override
	public TossPaymentResponse cancel(TossPaymentCancelRequest request) {
		try {
			return restClient.post()
				.uri("/v1/payments/{paymentKey}/cancel", request.paymentKey())
				.header(HttpHeaders.AUTHORIZATION, properties.authorizationHeader())
				.header(IDEMPOTENCY_KEY_HEADER, "cancel-" + request.paymentKey())
				.contentType(MediaType.APPLICATION_JSON)
				.body(new TossCancelBody(request.cancelReason()))
				.retrieve()
				.body(TossPaymentResponse.class);
		} catch (IllegalStateException exception) {
			throw new BusinessException(ErrorCode.TOSS_PAYMENTS_NOT_CONFIGURED);
		} catch (RestClientException exception) {
			throw new BusinessException(ErrorCode.TOSS_PAYMENTS_REQUEST_FAILED, exception.getMessage());
		}
	}

	private record TossCancelBody(String cancelReason) {
	}
}
