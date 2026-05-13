// API 실패 응답의 에러 코드, 메시지, 필드 오류를 표현하는 객체
package com.kitschcatch.backend.common.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.kitschcatch.backend.common.exception.ErrorCode;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record ApiError(
	String code,
	String message,
	List<FieldError> fieldErrors
) {

	public static ApiError from(ErrorCode errorCode) {
		return new ApiError(errorCode.getCode(), errorCode.getMessage(), List.of());
	}

	public static ApiError of(ErrorCode errorCode, String message) {
		return new ApiError(errorCode.getCode(), message, List.of());
	}

	public static ApiError of(ErrorCode errorCode, List<FieldError> fieldErrors) {
		return new ApiError(errorCode.getCode(), errorCode.getMessage(), fieldErrors);
	}

	public record FieldError(
		String field,
		String message
	) {
	}
}
