package com.kitschcatch.backend.domain.payment.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.kitschcatch.backend.domain.payment.dto.CancelPaymentRequest;
import com.kitschcatch.backend.domain.payment.dto.ConfirmPaymentRequest;
import com.kitschcatch.backend.domain.payment.dto.CreatePaymentRequest;
import com.kitschcatch.backend.domain.payment.dto.PaymentResponse;
import com.kitschcatch.backend.domain.payment.entity.PaymentMethod;
import com.kitschcatch.backend.domain.payment.entity.PaymentStatus;
import com.kitschcatch.backend.domain.payment.service.PaymentService;
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

class PaymentControllerTest {

	private PaymentService paymentService;
	private MockMvc mockMvc;
	private UsernamePasswordAuthenticationToken authentication;

	@BeforeEach
	void setUp() {
		paymentService = mock(PaymentService.class);
		mockMvc = MockMvcBuilders.standaloneSetup(new PaymentController(paymentService))
			.setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
			.setControllerAdvice(new ResponseStatusSetterAdvice(), new GlobalExceptionHandler())
			.build();
		authentication = new UsernamePasswordAuthenticationToken(new AuthenticatedUser(2L), null, List.of());
		SecurityContextHolder.getContext().setAuthentication(authentication);
	}

	@AfterEach
	void tearDown() {
		SecurityContextHolder.clearContext();
	}

	@Test
	@DisplayName("결제 생성 API는 토스 SDK 요청 값을 반환한다")
	void createPaymentReturnsTossSdkRequestValues() throws Exception {
		when(paymentService.createPayment(eq(2L), any(CreatePaymentRequest.class)))
			.thenReturn(paymentResponse(PaymentStatus.REQUESTED));

		mockMvc.perform(post("/api/payments")
				.principal(authentication)
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "orderId": 30,
					  "method": "CARD"
					}
					"""))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.data.paymentId").value(40))
			.andExpect(jsonPath("$.data.pgOrderId").value("KC-PAY-40"))
			.andExpect(jsonPath("$.data.clientKey").value("test_ck"))
			.andExpect(jsonPath("$.data.orderName").value("키링"))
			.andExpect(jsonPath("$.data.successUrl").value("https://example.com/payments/success?paymentId=40"))
			.andExpect(jsonPath("$.data.failUrl").value("https://example.com/payments/fail?paymentId=40"));
	}

	@Test
	@DisplayName("결제 승인 API는 승인된 결제 상태를 반환한다")
	void confirmPaymentReturnsApprovedPayment() throws Exception {
		when(paymentService.confirmPayment(eq(2L), eq(40L), any(ConfirmPaymentRequest.class)))
			.thenReturn(paymentResponse(PaymentStatus.APPROVED));

		mockMvc.perform(post("/api/payments/40/confirm")
				.principal(authentication)
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "paymentKey": "pg-key"
					}
					"""))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.status").value("APPROVED"));
	}

	@Test
	@DisplayName("결제 조회 API는 결제 상태를 반환한다")
	void getPaymentReturnsPayment() throws Exception {
		when(paymentService.getPayment(2L, 40L))
			.thenReturn(paymentResponse(PaymentStatus.REQUESTED));

		mockMvc.perform(get("/api/payments/40")
				.principal(authentication))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.paymentId").value(40))
			.andExpect(jsonPath("$.data.status").value("REQUESTED"));
	}

	@Test
	@DisplayName("결제 취소 API는 취소된 결제 상태를 반환한다")
	void cancelPaymentReturnsCanceledPayment() throws Exception {
		when(paymentService.cancelPayment(eq(2L), eq(40L), any(CancelPaymentRequest.class)))
			.thenReturn(paymentResponse(PaymentStatus.CANCELED));

		mockMvc.perform(post("/api/payments/40/cancel")
				.principal(authentication)
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "cancelReason": "단순 변심"
					}
					"""))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.status").value("CANCELED"));
	}

	private PaymentResponse paymentResponse(PaymentStatus status) {
		return new PaymentResponse(
			40L,
			30L,
			"KC-PAY-40",
			12000L,
			PaymentMethod.CARD,
			status,
			"키링",
			"test_ck",
			"https://example.com/payments/success?paymentId=40",
			"https://example.com/payments/fail?paymentId=40",
			LocalDateTime.of(2026, 5, 21, 14, 15),
			LocalDateTime.of(2026, 5, 21, 14, 0),
			LocalDateTime.of(2026, 5, 21, 14, 0)
		);
	}
}
