package com.kitschcatch.backend.domain.payment.client;

public class TossPaymentException extends RuntimeException {

	private final Integer statusCode;

	public TossPaymentException(String message) {
		super(message);
		this.statusCode = null;
	}

	public TossPaymentException(String message, int statusCode) {
		super(message);
		this.statusCode = statusCode;
	}

	public TossPaymentException(String message, Throwable cause) {
		super(message, cause);
		this.statusCode = null;
	}

	public TossPaymentException(String message, int statusCode, Throwable cause) {
		super(message, cause);
		this.statusCode = statusCode;
	}

	public boolean isClientError() {
		return statusCode != null && statusCode >= 400 && statusCode < 500;
	}
}
