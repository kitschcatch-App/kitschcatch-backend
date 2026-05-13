// 컨트롤러에서 발생한 예외를 공통 에러 응답으로 변환하는 전역 핸들러
package com.kitschcatch.backend.common.exception;

import com.kitschcatch.backend.common.response.ApiError;
import com.kitschcatch.backend.common.response.ApiResponse;
import java.util.Comparator;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class GlobalExceptionHandler {

	@ExceptionHandler(BusinessException.class)
	public ResponseEntity<ApiResponse<Void>> handleBusinessException(BusinessException exception) {
		ErrorCode errorCode = exception.getErrorCode();
		return ResponseEntity.status(errorCode.getHttpStatus())
			.body(ApiResponse.error(ApiError.of(errorCode, exception.getMessage())));
	}

	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ResponseEntity<ApiResponse<Void>> handleMethodArgumentNotValidException(
		MethodArgumentNotValidException exception
	) {
		ErrorCode errorCode = ErrorCode.INVALID_INPUT_VALUE;
		List<ApiError.FieldError> fieldErrors = exception.getBindingResult()
			.getFieldErrors()
			.stream()
			.map(fieldError -> new ApiError.FieldError(fieldError.getField(), fieldError.getDefaultMessage()))
			.sorted(Comparator.comparing(ApiError.FieldError::field))
			.toList();

		return ResponseEntity.status(errorCode.getHttpStatus())
			.body(ApiResponse.error(ApiError.of(errorCode, fieldErrors)));
	}

	@ExceptionHandler({
		MethodArgumentTypeMismatchException.class,
		MissingServletRequestParameterException.class,
		HttpMessageNotReadableException.class
	})
	public ResponseEntity<ApiResponse<Void>> handleBadRequestException(Exception exception) {
		ErrorCode errorCode = ErrorCode.BAD_REQUEST;
		return ResponseEntity.status(errorCode.getHttpStatus())
			.body(ApiResponse.error(ApiError.from(errorCode)));
	}

	@ExceptionHandler(Exception.class)
	public ResponseEntity<ApiResponse<Void>> handleException(Exception exception) {
		ErrorCode errorCode = ErrorCode.INTERNAL_SERVER_ERROR;
		return ResponseEntity.status(errorCode.getHttpStatus())
			.body(ApiResponse.error(ApiError.from(errorCode)));
	}
}
