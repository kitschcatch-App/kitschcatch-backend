package com.kitschcatch.backend.domain.order.controller;

import com.kitschcatch.backend.domain.order.dto.CreateOrderRequest;
import com.kitschcatch.backend.domain.order.dto.OrderResponse;
import com.kitschcatch.backend.domain.order.service.OrderService;
import com.kitschcatch.backend.global.response.ApiResponse;
import com.kitschcatch.backend.global.security.AuthenticatedUser;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/orders")
public class OrderController {

	private final OrderService orderService;

	public OrderController(OrderService orderService) {
		this.orderService = orderService;
	}

	@PostMapping
	public ApiResponse<OrderResponse> createOrder(
		@AuthenticationPrincipal AuthenticatedUser user,
		@Valid @RequestBody CreateOrderRequest request
	) {
		return ApiResponse.created(orderService.createOrder(user.userId(), request));
	}
}
