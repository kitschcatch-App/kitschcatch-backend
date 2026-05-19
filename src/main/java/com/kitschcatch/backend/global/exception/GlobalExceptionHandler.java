// 컨트롤러에서 발생한 예외를 공통 에러 응답으로 변환하는 전역 핸들러
package com.kitschcatch.backend.global.exception;

import com.kitschcatch.backend.global.response.ApiError;
import com.kitschcatch.backend.global.response.ApiResponse;
import java.util.Comparator;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class GlobalExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

	@ExceptionHandler(BusinessException.class)
	public ApiResponse<Void> handleBusinessException(BusinessException exception) {
		ErrorCode errorCode = exception.getErrorCode();
		return ApiResponse.fail(errorCode, exception.getMessage());
	}

	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ApiResponse<Void> handleMethodArgumentNotValidException(
		MethodArgumentNotValidException exception
	) {
		ErrorCode errorCode = ErrorCode.INVALID_INPUT_VALUE;
		List<ApiError.FieldError> fieldErrors = exception.getBindingResult()
			.getFieldErrors()
			.stream()
			.map(fieldError -> new ApiError.FieldError(fieldError.getField(), fieldError.getDefaultMessage()))
			.sorted(Comparator.comparing(ApiError.FieldError::field))
			.toList();

		return ApiResponse.fail(errorCode, fieldErrors);
	}

	@ExceptionHandler({
		MethodArgumentTypeMismatchException.class,
		MissingServletRequestParameterException.class,
		HttpMessageNotReadableException.class
	})
	public ApiResponse<Void> handleBadRequestException(Exception exception) {
		ErrorCode errorCode = ErrorCode.BAD_REQUEST;
		return ApiResponse.fail(errorCode);
	}

	@ExceptionHandler(Exception.class)
	public ApiResponse<Void> handleException(Exception exception) {
		ErrorCode errorCode = ErrorCode.INTERNAL_SERVER_ERROR;
		log.error("Unhandled exception occurred", exception);
		return ApiResponse.fail(errorCode);
	}
}
