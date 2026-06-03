// API 실패 응답의 에러 코드, 메시지, 필드 오류를 표현하는 객체
package com.kitschcatch.backend.global.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.kitschcatch.backend.global.exception.ErrorCode;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_EMPTY)
@Schema(description = "API 실패 응답 에러 정보")
public record ApiError(
	@Schema(description = "내부 에러 코드")
	String code,

	@Schema(description = "에러 메시지")
	String message,

	@Schema(description = "필드별 검증 오류 목록")
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

	@Schema(description = "필드 검증 오류")
	public record FieldError(
		@Schema(description = "오류가 발생한 필드명")
		String field,

		@Schema(description = "필드 오류 메시지")
		String message
	) {
	}
}
