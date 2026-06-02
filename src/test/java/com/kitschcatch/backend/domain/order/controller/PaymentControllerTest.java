// 결제 API의 생성, 승인, 조회, 취소 응답을 검증하는 테스트
package com.kitschcatch.backend.domain.order.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.kitschcatch.backend.domain.order.dto.ConfirmPaymentRequest;
import com.kitschcatch.backend.domain.order.dto.CreatePaymentRequest;
import com.kitschcatch.backend.domain.order.dto.CreatePaymentResponse;
import com.kitschcatch.backend.domain.order.dto.PaymentResponse;
import com.kitschcatch.backend.domain.order.entity.PaymentStatus;
import com.kitschcatch.backend.domain.order.service.PaymentService;
import com.kitschcatch.backend.global.exception.GlobalExceptionHandler;
import com.kitschcatch.backend.global.response.ResponseStatusSetterAdvice;
import com.kitschcatch.backend.global.security.AuthenticatedUser;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

class PaymentControllerTest {

	private PaymentService paymentService;
	private MockMvc mockMvc;
	private UsernamePasswordAuthenticationToken authentication;

	@BeforeEach
	void setUp() {
		paymentService = mock(PaymentService.class);
		LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
		validator.afterPropertiesSet();

		mockMvc = MockMvcBuilders.standaloneSetup(new PaymentController(paymentService))
			.setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
			.setControllerAdvice(new ResponseStatusSetterAdvice(), new GlobalExceptionHandler())
			.setValidator(validator)
			.build();
		authentication = new UsernamePasswordAuthenticationToken(new AuthenticatedUser(1L), null, List.of());
		SecurityContextHolder.getContext().setAuthentication(authentication);
	}

	@AfterEach
	void tearDown() {
		SecurityContextHolder.clearContext();
	}

	@Test
	@DisplayName("결제 생성 API는 주문 번호 기준으로 결제 대기 정보를 반환한다")
	void createPaymentReturnsReadyPayment() throws Exception {
		when(paymentService.createPayment(eq(1L), any(CreatePaymentRequest.class)))
			.thenReturn(new CreatePaymentResponse("PAY-999", PaymentStatus.READY));

		mockMvc.perform(post("/api/payments")
				.principal(authentication)
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "orderId": "ORD-123",
					  "amount": 650000,
					  "paymentMethod": "CARD"
					}
					"""))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.data.paymentId").value("PAY-999"))
			.andExpect(jsonPath("$.data.status").value("READY"));
	}

	@Test
	@DisplayName("결제 승인 API는 결제 상태를 성공으로 반환한다")
	void confirmPaymentReturnsSuccessPayment() throws Exception {
		when(paymentService.confirmPayment(eq(1L), eq("PAY-999"), any(ConfirmPaymentRequest.class)))
			.thenReturn(paymentResponse(PaymentStatus.SUCCESS));

		mockMvc.perform(post("/api/payments/PAY-999/confirm")
				.principal(authentication)
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "paymentId": "PAY-999",
					  "paymentToken": "pg-token"
					}
					"""))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.paymentId").value("PAY-999"))
			.andExpect(jsonPath("$.data.status").value("SUCCESS"));
	}

	@Test
	@DisplayName("결제 조회 API는 결제 상세 정보를 반환한다")
	void getPaymentReturnsPaymentDetail() throws Exception {
		when(paymentService.getPayment(1L, "PAY-999")).thenReturn(paymentResponse(PaymentStatus.SUCCESS));

		mockMvc.perform(get("/api/payments/PAY-999")
				.principal(authentication))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.paymentId").value("PAY-999"))
			.andExpect(jsonPath("$.data.orderId").value("ORD-123"))
			.andExpect(jsonPath("$.data.amount").value(650000));
	}

	@Test
	@DisplayName("결제 취소 API는 취소된 결제 상세 정보를 반환한다")
	void cancelPaymentReturnsCanceledPayment() throws Exception {
		when(paymentService.cancelPayment(1L, "PAY-999")).thenReturn(paymentResponse(PaymentStatus.CANCELED));

		mockMvc.perform(post("/api/payments/PAY-999/cancel")
				.principal(authentication)
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "paymentId": "PAY-999"
					}
					"""))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.paymentId").value("PAY-999"))
			.andExpect(jsonPath("$.data.status").value("CANCELED"));
	}

	private PaymentResponse paymentResponse(PaymentStatus status) {
		return new PaymentResponse(
			"PAY-999",
			"ORD-123",
			650000L,
			status,
			LocalDateTime.of(2026, 4, 13, 15, 0),
			LocalDateTime.of(2026, 4, 13, 15, 1, 10)
		);
	}
}
