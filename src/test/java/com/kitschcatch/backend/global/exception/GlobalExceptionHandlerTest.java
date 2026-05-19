// 공통 응답 및 전역 예외 처리 HTTP 응답 형식을 검증하는 테스트
package com.kitschcatch.backend.global.exception;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.kitschcatch.backend.global.response.ApiResponse;
import com.kitschcatch.backend.global.response.ResponseStatusSetterAdvice;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

class GlobalExceptionHandlerTest {

	private MockMvc mockMvc;

	@BeforeEach
	void setUp() {
		LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
		validator.afterPropertiesSet();

		mockMvc = MockMvcBuilders.standaloneSetup(new TestApiController())
			.setControllerAdvice(new ResponseStatusSetterAdvice(), new GlobalExceptionHandler())
			.setValidator(validator)
			.build();
	}

	@Test
	@DisplayName("성공 응답을 공통 응답 형식으로 반환한다")
	void successResponseUsesCommonFormat() throws Exception {
		mockMvc.perform(get("/test/success"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.name").value("kitsch"))
			.andExpect(jsonPath("$.error").doesNotExist());
	}

	@Test
	@DisplayName("생성 성공 응답은 ApiResponse의 HTTP 상태를 실제 응답 상태로 사용한다")
	void createdResponseUsesApiResponseHttpStatus() throws Exception {
		mockMvc.perform(get("/test/success-created"))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.name").value("kitsch"))
			.andExpect(jsonPath("$.error").doesNotExist());
	}

	@Test
	@DisplayName("비즈니스 예외를 정의된 에러 코드와 메시지로 반환한다")
	void businessExceptionUsesDefinedErrorCode() throws Exception {
		mockMvc.perform(get("/test/business-exception"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.error.code").value("COMMON_001"))
			.andExpect(jsonPath("$.error.message").value("입력값이 올바르지 않습니다."))
			.andExpect(jsonPath("$.error.fieldErrors").doesNotExist());
	}

	@Test
	@DisplayName("Validation 예외를 필드 오류 목록과 함께 반환한다")
	void validationExceptionIncludesFieldErrors() throws Exception {
		mockMvc.perform(post("/test/validation")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.error.code").value("COMMON_001"))
			.andExpect(jsonPath("$.error.message").value("입력값이 올바르지 않습니다."))
			.andExpect(jsonPath("$.error.fieldErrors[0].field").value("name"))
			.andExpect(jsonPath("$.error.fieldErrors[0].message").value("이름은 필수입니다."));
	}

	@Test
	@DisplayName("잘못된 요청 파라미터를 공통 에러 형식으로 반환한다")
	void invalidRequestParameterUsesCommonErrorFormat() throws Exception {
		mockMvc.perform(get("/test/type-mismatch").param("count", "abc"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.error.code").value("COMMON_002"))
			.andExpect(jsonPath("$.error.message").value("잘못된 요청입니다."))
			.andExpect(jsonPath("$.error.fieldErrors").doesNotExist());
	}

	@Test
	@DisplayName("처리하지 못한 서버 예외를 공통 에러 형식으로 반환한다")
	void unhandledExceptionUsesCommonErrorFormat() throws Exception {
		mockMvc.perform(get("/test/server-error"))
			.andExpect(status().isInternalServerError())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.error.code").value("COMMON_999"))
			.andExpect(jsonPath("$.error.message").value("서버 내부 오류가 발생했습니다."))
			.andExpect(jsonPath("$.error.fieldErrors").doesNotExist());
	}

	@RestController
	static class TestApiController {

		@GetMapping("/test/success")
		ApiResponse<Map<String, String>> success() {
			return ApiResponse.success(Map.of("name", "kitsch"));
		}

		@GetMapping("/test/success-created")
		ApiResponse<Map<String, String>> created() {
			return ApiResponse.created(Map.of("name", "kitsch"));
		}

		@GetMapping("/test/business-exception")
		ApiResponse<Void> businessException() {
			throw new BusinessException(ErrorCode.INVALID_INPUT_VALUE);
		}

		@PostMapping("/test/validation")
		ApiResponse<TestRequest> validation(@Valid @RequestBody TestRequest request) {
			return ApiResponse.success(request);
		}

		@GetMapping("/test/type-mismatch")
		ApiResponse<Map<String, Integer>> typeMismatch(@RequestParam Integer count) {
			return ApiResponse.success(Map.of("count", count));
		}

		@GetMapping("/test/server-error")
		ApiResponse<Void> serverError() {
			throw new RuntimeException("unexpected");
		}
	}

	record TestRequest(
		@NotBlank(message = "이름은 필수입니다.")
		String name
	) {
	}
}
