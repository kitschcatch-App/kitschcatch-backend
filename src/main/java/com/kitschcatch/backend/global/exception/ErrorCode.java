// 공통 예외 상황별 HTTP 상태와 내부 에러 코드를 관리하는 enum
package com.kitschcatch.backend.global.exception;

import org.springframework.http.HttpStatus;

public enum ErrorCode {

	INVALID_INPUT_VALUE(HttpStatus.BAD_REQUEST, "COMMON_001", "입력값이 올바르지 않습니다."),
	BAD_REQUEST(HttpStatus.BAD_REQUEST, "COMMON_002", "잘못된 요청입니다."),
	KAKAO_LOGIN_FAILED(HttpStatus.UNAUTHORIZED, "AUTH_001", "카카오 로그인에 실패했습니다."),
	KAKAO_EMAIL_REQUIRED(HttpStatus.BAD_REQUEST, "AUTH_002", "카카오 이메일 동의가 필요합니다."),
	INVALID_REFRESH_TOKEN(HttpStatus.UNAUTHORIZED, "AUTH_003", "리프레시 토큰이 올바르지 않습니다."),
	INVALID_AUTH_TOKEN(HttpStatus.UNAUTHORIZED, "AUTH_004", "인증 토큰이 올바르지 않습니다."),
	POST_NOT_FOUND(HttpStatus.NOT_FOUND, "POST_001", "판매 게시글을 찾을 수 없습니다."),
	POST_FORBIDDEN(HttpStatus.FORBIDDEN, "POST_002", "판매 게시글에 접근할 수 없습니다."),
	POST_IMAGE_REQUIRED(HttpStatus.BAD_REQUEST, "POST_003", "판매 게시글 사진은 필수입니다."),
	POST_IMAGE_INVALID(HttpStatus.BAD_REQUEST, "POST_004", "판매 게시글 사진 정보가 올바르지 않습니다."),
	POST_IMAGE_NOT_UPLOADED(HttpStatus.BAD_REQUEST, "POST_005", "업로드된 판매 게시글 사진을 찾을 수 없습니다."),
	POST_INVALID_STATE(HttpStatus.CONFLICT, "POST_006", "판매 게시글 상태가 올바르지 않습니다."),
	ORDER_NOT_FOUND(HttpStatus.NOT_FOUND, "ORDER_001", "주문을 찾을 수 없습니다."),
	ORDER_UNAVAILABLE(HttpStatus.CONFLICT, "ORDER_002", "주문할 수 없는 판매 게시글입니다."),
	ORDER_EXPIRED(HttpStatus.BAD_REQUEST, "ORDER_003", "주문 결제 가능 시간이 만료되었습니다."),
	PAYMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "PAYMENT_001", "결제를 찾을 수 없습니다."),
	PAYMENT_INVALID_STATE(HttpStatus.BAD_REQUEST, "PAYMENT_002", "결제 상태가 올바르지 않습니다."),
	PAYMENT_PROVIDER_FAILED(HttpStatus.BAD_GATEWAY, "PAYMENT_003", "결제 대행사 요청에 실패했습니다."),
	PAYMENT_AMOUNT_MISMATCH(HttpStatus.BAD_REQUEST, "PAYMENT_004", "결제 금액이 주문 금액과 일치하지 않습니다."),
	PAYMENT_PROVIDER_RESPONSE_INVALID(HttpStatus.BAD_GATEWAY, "PAYMENT_005", "결제 대행사 응답이 올바르지 않습니다."),
	S3_BUCKET_NOT_CONFIGURED(HttpStatus.INTERNAL_SERVER_ERROR, "S3_001", "S3 버킷 설정이 필요합니다."),
	INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "COMMON_999", "서버 내부 오류가 발생했습니다.");

	private final HttpStatus httpStatus;
	private final String code;
	private final String message;

	ErrorCode(HttpStatus httpStatus, String code, String message) {
		this.httpStatus = httpStatus;
		this.code = code;
		this.message = message;
	}

	public HttpStatus getHttpStatus() {
		return httpStatus;
	}

	public String getCode() {
		return code;
	}

	public String getMessage() {
		return message;
	}
}
