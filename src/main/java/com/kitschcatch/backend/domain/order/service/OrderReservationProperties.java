// 주문의 상품 예약 유효 기간을 설정한다.
package com.kitschcatch.backend.domain.order.service;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("app.orders")
public record OrderReservationProperties(@DefaultValue("15m") Duration reservationTtl) {
	public OrderReservationProperties {
		if (reservationTtl == null || reservationTtl.isNegative() || reservationTtl.isZero()) {
			throw new IllegalArgumentException("예약 유효 기간은 양수여야 합니다.");
		}
	}
}
