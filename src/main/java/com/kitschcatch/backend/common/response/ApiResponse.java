// API 성공 및 실패 응답의 최상위 형식을 표현하는 공통 응답 객체
package com.kitschcatch.backend.common.response;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiResponse<T>(
	boolean success,
	T data,
	ApiError error
) {

	public static <T> ApiResponse<T> success(T data) {
		return new ApiResponse<>(true, data, null);
	}

	public static <T> ApiResponse<T> error(ApiError error) {
		return new ApiResponse<>(false, null, error);
	}
}
