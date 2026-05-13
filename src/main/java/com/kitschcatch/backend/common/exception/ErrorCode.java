// 공통 예외 상황별 HTTP 상태와 내부 에러 코드를 관리하는 enum
package com.kitschcatch.backend.common.exception;

import org.springframework.http.HttpStatus;

public enum ErrorCode {

	INVALID_INPUT_VALUE(HttpStatus.BAD_REQUEST, "COMMON_001", "입력값이 올바르지 않습니다."),
	BAD_REQUEST(HttpStatus.BAD_REQUEST, "COMMON_002", "잘못된 요청입니다."),
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
