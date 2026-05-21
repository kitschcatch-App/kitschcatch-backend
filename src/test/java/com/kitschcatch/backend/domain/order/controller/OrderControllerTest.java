package com.kitschcatch.backend.domain.order.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.kitschcatch.backend.domain.order.dto.CreateOrderRequest;
import com.kitschcatch.backend.domain.order.dto.OrderResponse;
import com.kitschcatch.backend.domain.order.entity.OrderStatus;
import com.kitschcatch.backend.domain.order.service.OrderService;
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

class OrderControllerTest {

	private OrderService orderService;
	private MockMvc mockMvc;
	private UsernamePasswordAuthenticationToken authentication;

	@BeforeEach
	void setUp() {
		orderService = mock(OrderService.class);
		mockMvc = MockMvcBuilders.standaloneSetup(new OrderController(orderService))
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
	@DisplayName("주문 생성 API는 판매글 기준 주문을 생성한다")
	void createOrderReturnsCreatedOrder() throws Exception {
		when(orderService.createOrder(eq(2L), any(CreateOrderRequest.class)))
			.thenReturn(new OrderResponse(
				30L,
				10L,
				2L,
				12000L,
				OrderStatus.PENDING,
				LocalDateTime.of(2026, 5, 21, 14, 15),
				LocalDateTime.of(2026, 5, 21, 14, 0),
				LocalDateTime.of(2026, 5, 21, 14, 0)
			));

		mockMvc.perform(post("/api/orders")
				.principal(authentication)
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "postId": 10
					}
					"""))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.id").value(30))
			.andExpect(jsonPath("$.data.postId").value(10))
			.andExpect(jsonPath("$.data.amount").value(12000))
			.andExpect(jsonPath("$.data.orderStatus").value("PENDING"));
	}
}
