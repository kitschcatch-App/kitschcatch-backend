// 주문 API의 HTTP 요청과 공통 응답 형식을 검증하는 테스트
package com.kitschcatch.backend.domain.order.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.kitschcatch.backend.domain.order.dto.CreateOrderRequest;
import com.kitschcatch.backend.domain.order.dto.CreateOrderResponse;
import com.kitschcatch.backend.domain.order.entity.PaymentStatus;
import com.kitschcatch.backend.domain.order.service.OrderService;
import com.kitschcatch.backend.global.exception.GlobalExceptionHandler;
import com.kitschcatch.backend.global.response.ResponseStatusSetterAdvice;
import com.kitschcatch.backend.global.security.AuthenticatedUser;
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

class OrderControllerTest {

	private OrderService orderService;
	private MockMvc mockMvc;
	private UsernamePasswordAuthenticationToken authentication;

	@BeforeEach
	void setUp() {
		orderService = mock(OrderService.class);
		LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
		validator.afterPropertiesSet();

		mockMvc = MockMvcBuilders.standaloneSetup(new OrderController(orderService))
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
	@DisplayName("주문 생성 API는 판매 게시글 기준으로 주문과 결제 대기 정보를 반환한다")
	void createOrderReturnsReadyPayment() throws Exception {
		when(orderService.createOrder(eq(1L), any(CreateOrderRequest.class)))
			.thenReturn(new CreateOrderResponse("ORD-123", "PAY-999", PaymentStatus.READY));

		mockMvc.perform(post("/api/orders")
				.principal(authentication)
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "postId": 10,
					  "amount": 650000,
					  "paymentMethod": "CARD"
					}
					"""))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.orderId").value("ORD-123"))
			.andExpect(jsonPath("$.data.paymentId").value("PAY-999"))
			.andExpect(jsonPath("$.data.status").value("READY"));
	}

	@Test
	@DisplayName("주문 생성 API는 판매 게시글 ID가 없으면 검증 오류를 반환한다")
	void createOrderWithoutPostIdReturnsBadRequest() throws Exception {
		mockMvc.perform(post("/api/orders")
				.principal(authentication)
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "amount": 650000,
					  "paymentMethod": "CARD"
					}
					"""))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.error.code").value("COMMON_001"))
			.andExpect(jsonPath("$.error.fieldErrors[0].field").value("postId"));
	}
}
