package com.kitschcatch.backend.domain.order.dto;

import jakarta.validation.constraints.NotNull;

public record CreateOrderRequest(
	@NotNull
	Long postId
) {
}
