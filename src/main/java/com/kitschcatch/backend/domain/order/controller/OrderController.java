// 주문 생성 HTTP API를 처리하는 컨트롤러
package com.kitschcatch.backend.domain.order.controller;

import com.kitschcatch.backend.domain.order.dto.CreateOrderRequest;
import com.kitschcatch.backend.domain.order.dto.CreateOrderResponse;
import com.kitschcatch.backend.domain.order.service.OrderService;
import com.kitschcatch.backend.global.response.ApiResponse;
import com.kitschcatch.backend.global.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/orders")
@Tag(name = "주문", description = "판매 게시글 기준 주문 생성 API")
@SecurityRequirement(name = "bearerAuth")
public class OrderController {

	private final OrderService orderService;

	public OrderController(OrderService orderService) {
		this.orderService = orderService;
	}

	@PostMapping
	@Operation(summary = "주문 생성", description = "판매 게시글과 결제 금액, 결제 수단으로 주문을 생성합니다.")
	public ApiResponse<CreateOrderResponse> createOrder(
		@AuthenticationPrincipal AuthenticatedUser user,
		@Valid @RequestBody CreateOrderRequest request
	) {
		return ApiResponse.created(orderService.createOrder(user.userId(), request));
	}
}
