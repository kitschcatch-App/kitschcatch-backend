// API 성공 및 실패 응답의 최상위 형식을 표현하는 공통 응답 객체
package com.kitschcatch.backend.global.response;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.kitschcatch.backend.global.exception.ErrorCode;
import java.util.List;
import org.springframework.http.HttpStatus;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiResponse<T>(
	@JsonIgnore
	HttpStatus httpStatus,
	boolean success,
	T data,
	ApiError error
) {

	public static <T> ApiResponse<T> success(T data) {
		return ok(data);
	}

	public static <T> ApiResponse<T> ok(T data) {
		return new ApiResponse<>(HttpStatus.OK, true, data, null);
	}

	public static <T> ApiResponse<T> created(T data) {
		return new ApiResponse<>(HttpStatus.CREATED, true, data, null);
	}

	public static <T> ApiResponse<T> fail(ErrorCode errorCode) {
		return new ApiResponse<>(errorCode.getHttpStatus(), false, null, ApiError.from(errorCode));
	}

	public static <T> ApiResponse<T> fail(ErrorCode errorCode, String message) {
		return new ApiResponse<>(errorCode.getHttpStatus(), false, null, ApiError.of(errorCode, message));
	}

	public static <T> ApiResponse<T> fail(ErrorCode errorCode, List<ApiError.FieldError> fieldErrors) {
		return new ApiResponse<>(errorCode.getHttpStatus(), false, null, ApiError.of(errorCode, fieldErrors));
	}
}
